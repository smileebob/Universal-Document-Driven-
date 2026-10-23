package com.example.gateway.filter;

import com.example.gateway.config.GatewayAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关 JWT 认证全局过滤器（GW-07：网关只做路由/认证/限流/审计埋点）。
 *
 * <p>职责：校验 Authorization: Bearer 令牌（HS256，密钥经 GATEWAY_JWT_SECRET 注入，R-05），
 * 强制 subject / expiration 存在且有效，按配置校验 issuer/audience；解出身份后以
 * X-User-Id / X-User-Roles / X-User-Dept 透传下游。</p>
 *
 * <p>信任边界：所有认证分支（含关闭认证与 skip-paths）先移除外部身份头，仅 dev demo
 * 配置允许保留前端演示身份；客户端 IP 仅在直连方属于 trusted-proxies 时解析入站 XFF 链，
 * 否则一律取 TCP 对端，并统一写入 X-Forwarded-For / X-Real-IP。</p>
 *
 * <p>放行规则从简、留扩展点：doc.gateway.auth.skip-paths（Ant 风格）；
 * /api/v1/internal/** 在网关无路由天然不可达（A-07），无需额外放行。</p>
 *
 * <p>失败语义：HTTP 401 + 简单 JSON 体；统一鉴权错误码待 PRD 12 回写后收口（B2 禁自造码段）。</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    /** 下游透传头：用户标识与角色（由网关注入，外部同名头一律剥离） */
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    /** 部门头：身份头之一（仅由网关认证后注入） */
    public static final String USER_DEPT_HEADER = "X-User-Dept";
    /** 转发链头：网关重写为可信客户端地址 */
    public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
    /** 可信客户端地址（供下游审计；与 XFF 同源） */
    public static final String X_REAL_IP_HEADER = "X-Real-IP";

    private static final String BEARER_PREFIX = "Bearer ";

    /** HS256 最小密钥长度（字节） */
    private static final int MIN_SECRET_BYTES = 32;

    private final GatewayAuthProperties authProperties;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthGlobalFilter(GatewayAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean demoIdentity = !authProperties.isEnabled()
                && authProperties.isAllowDemoIdentityHeaders();
        // 信任边界清洗（所有分支）：剥离外部身份头，重写客户端 IP
        ServerWebExchange sanitized = sanitizeTrustBoundary(exchange, demoIdentity);
        // 认证开关：仅 dev demo 可关闭，生产由 GatewayAuthStartupGuard 强制开启
        if (!authProperties.isEnabled()) {
            return chain.filter(sanitized);
        }
        ServerHttpRequest request = sanitized.getRequest();
        String path = request.getPath().value();
        // 放行路径（Ant 风格匹配，扩展点：skip-paths 配置追加）
        if (isSkipPath(path)) {
            return chain.filter(sanitized);
        }
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(sanitized, "缺少 Bearer 令牌");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = parseAndValidateToken(token);
            String userId = claims.getSubject();
            String roles = extractRoles(claims);
            String dept = extractDept(claims);
            // 透传前先剥离外部同名头，防止伪造；再由网关解出的身份写入
            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> {
                        h.remove(USER_ID_HEADER);
                        h.remove(USER_ROLES_HEADER);
                        h.remove(USER_DEPT_HEADER);
                        h.set(USER_ID_HEADER, userId);
                        if (roles != null && !roles.isBlank()) {
                            h.set(USER_ROLES_HEADER, roles);
                        }
                        if (dept != null && !dept.isBlank()) {
                            h.set(USER_DEPT_HEADER, dept);
                        }
                    })
                    .build();
            return chain.filter(sanitized.mutate().request(mutated).build());
        } catch (JwtException | IllegalArgumentException e) {
            // 令牌无效/过期/签名不符：统一 401，不泄漏具体原因细节（H-01）
            log.warn("[gateway-auth] JWT 校验失败，path={}, reason={}", path, e.getMessage());
            return unauthorized(sanitized, "令牌无效或已过期");
        }
    }

    /**
     * 信任边界清洗：移除外部身份头，重写客户端地址头。
     *
     * @param exchange        入站交换上下文
     * @param keepDemoIdentity 仅 dev demo：保留前端演示身份头（Dept 始终移除）
     * @return 清洗后的交换上下文
     */
    private ServerWebExchange sanitizeTrustBoundary(ServerWebExchange exchange,
                                                    boolean keepDemoIdentity) {
        ServerHttpRequest request = exchange.getRequest();
        String clientIp = resolveClientIp(request);
        boolean demo = keepDemoIdentity;
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    if (!demo) {
                        h.remove(USER_ID_HEADER);
                        h.remove(USER_ROLES_HEADER);
                    }
                    h.remove(USER_DEPT_HEADER);
                    if (clientIp != null) {
                        h.set(X_FORWARDED_FOR_HEADER, clientIp);
                        h.set(X_REAL_IP_HEADER, clientIp);
                    }
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    /**
     * 解析可信客户端 IP：
     * <ul>
     *   <li>直连方不在 trusted-proxies：忽略入站 XFF，返回 TCP 对端；</li>
     *   <li>直连方受信：从右向左跳过可信代理，返回首个非可信地址。</li>
     * </ul>
     *
     * @param request 入站请求
     * @return 客户端 IP（不可判定时返回 TCP 对端，可空）
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String remoteIp = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .filter(Objects::nonNull)
                .map(addr -> addr.getHostAddress())
                .orElse(null);
        if (remoteIp == null) {
            return null;
        }
        List<String> trusted = authProperties.getTrustedProxies();
        if (trusted == null || trusted.isEmpty() || !trusted.contains(remoteIp)) {
            return remoteIp;
        }
        List<String> chain = new ArrayList<>();
        String forwarded = request.getHeaders().getFirst(X_FORWARDED_FOR_HEADER);
        if (forwarded != null) {
            for (String hop : forwarded.split(",")) {
                String value = hop.trim();
                if (!value.isEmpty()) {
                    chain.add(value);
                }
            }
        }
        chain.add(remoteIp);
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!trusted.contains(chain.get(i))) {
                return chain.get(i);
            }
        }
        return chain.get(0);
    }

    /**
     * 解析并校验 JWT：验签、subject/expiration 必填有效，按配置校验 issuer/audience。
     *
     * @param token 去 Bearer 前缀后的令牌
     * @return 载荷 Claims
     */
    private Claims parseAndValidateToken(String token) {
        String secret = authProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            // 配置缺失属部署错误（GATEWAY_JWT_SECRET 未注入），按拒绝处理并告警
            log.error("[gateway-auth] 未配置 doc.gateway.auth.secret（GATEWAY_JWT_SECRET），拒绝请求");
            throw new JwtException("gateway jwt secret not configured");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            log.error("[gateway-auth] JWT 密钥长度不足 {} 字节，拒绝请求（请注入强密钥）",
                    MIN_SECRET_BYTES);
            throw new JwtException("gateway jwt secret too short");
        }
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JwtException("missing subject");
        }
        Date expiration = claims.getExpiration();
        if (expiration == null || !expiration.after(new Date())) {
            throw new JwtException("missing or expired expiration");
        }
        String issuer = authProperties.getIssuer();
        if (issuer != null && !issuer.isBlank()
                && !issuer.equals(claims.getIssuer())) {
            throw new JwtException("issuer mismatch");
        }
        String audience = authProperties.getAudience();
        if (audience != null && !audience.isBlank()) {
            Set<String> audiences = claims.getAudience();
            if (audiences == null || !audiences.contains(audience)) {
                throw new JwtException("audience mismatch");
            }
        }
        return claims;
    }

    /**
     * 提取角色声明（roles 声明支持字符串或数组，数组以逗号连接）。
     *
     * @param claims JWT 载荷
     * @return 角色串，缺省 null
     */
    private String extractRoles(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?> roleList) {
            return String.join(",", roleList.stream().map(String::valueOf).toList());
        }
        return roles == null ? null : String.valueOf(roles);
    }

    /**
     * 提取部门声明（dept 优先，兼容 department；缺失返回 null）。
     *
     * @param claims JWT 载荷
     * @return 部门标识
     */
    private String extractDept(Claims claims) {
        Object dept = claims.get("dept");
        if (dept == null) {
            dept = claims.get("department");
        }
        return dept == null ? null : String.valueOf(dept);
    }

    /**
     * 放行路径判定（Ant 风格）。
     *
     * @param path 请求路径
     * @return true=放行
     */
    private boolean isSkipPath(String path) {
        for (String pattern : authProperties.getSkipPaths()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 401 响应（简单 JSON 体；鉴权错误码待 PRD 12 回写后统一收口）。
     *
     * @param exchange 交换上下文
     * @param message  可读文案
     * @return 响应写回 Mono
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"code\":401,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 次于 TraceIdGlobalFilter（最高优先级），保证拒绝场景日志亦带 traceId
        return 0;
    }
}
