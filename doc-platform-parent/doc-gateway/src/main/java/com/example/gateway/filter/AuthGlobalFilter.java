package com.example.gateway.filter;

import com.example.gateway.config.GatewayAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
 * 解出 subject 与 roles 后以 X-User-Id / X-User-Roles 透传下游；
 * 透传前先剥离外部同名头，防止伪造。</p>
 *
 * <p>信任边界（评审修复 Minor-13）：无论认证开关/放行路径，入站请求一律剥离
 * X-User-Dept（身份头仅由网关注入），并以真实 TCP remoteAddress 追加重写
 * X-Forwarded-For（外部入站 XFF 不可信，下游以末位为准）。</p>
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
    /** 部门头：身份头之一，入站一律剥离（仅由网关认证后注入，评审修复 Minor-13） */
    public static final String USER_DEPT_HEADER = "X-User-Dept";
    /** 转发链头：以真实 remoteAddress 追加重写（评审修复 Minor-13） */
    public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayAuthProperties authProperties;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthGlobalFilter(GatewayAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 信任边界清洗（评审修复 Minor-13）：无论后续认证分支，一律先生效
        ServerWebExchange sanitized = sanitizeTrustBoundary(exchange);
        // 认证开关：dev 联调可关闭，生产必须开启（doc.gateway.auth.enabled）
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
            Claims claims = parseToken(token);
            String userId = claims.getSubject();
            String roles = extractRoles(claims);
            // 透传前先剥离外部同名头，防止伪造；再由网关解出的身份写入
            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> {
                        h.remove(USER_ID_HEADER);
                        h.remove(USER_ROLES_HEADER);
                        if (userId != null) {
                            h.set(USER_ID_HEADER, userId);
                        }
                        if (roles != null) {
                            h.set(USER_ROLES_HEADER, roles);
                        }
                    })
                    .build();
            return chain.filter(sanitized.mutate().request(mutated).build());
        } catch (JwtException | IllegalArgumentException e) {
            // 令牌无效/过期/签名不符：统一 401，不泄漏具体原因细节（H-01）
            log.warn("[gateway-auth] JWT 校验失败，path={}", path);
            return unauthorized(sanitized, "令牌无效或已过期");
        }
    }

    /**
     * 信任边界清洗（评审修复 Minor-13）：
     * <ul>
     *   <li>剥离入站 X-User-Dept：身份类头仅由网关认证后注入，外部入站一律不可信；</li>
     *   <li>X-Forwarded-For 追加真实 TCP remoteAddress：下游以末位为准，
     *       外部预置的 XFF 链不再被盲信。</li>
     * </ul>
     *
     * @param exchange 入站交换上下文
     * @return 清洗后的交换上下文（无头变更时原样返回）
     */
    private ServerWebExchange sanitizeTrustBoundary(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String remoteIp = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(addr -> addr.getHostAddress())
                .orElse(null);
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.remove(USER_DEPT_HEADER);
                    if (remoteIp != null) {
                        String existing = h.getFirst(X_FORWARDED_FOR_HEADER);
                        h.set(X_FORWARDED_FOR_HEADER,
                                existing == null || existing.isBlank() ? remoteIp : existing + ", " + remoteIp);
                    }
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    /**
     * 解析并验签 JWT（jjwt 0.12.x API）。
     *
     * @param token 去 Bearer 前缀后的令牌
     * @return 载荷 Claims
     */
    private Claims parseToken(String token) {
        String secret = authProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            // 配置缺失属部署错误（GATEWAY_JWT_SECRET 未注入），按拒绝处理并告警
            log.error("[gateway-auth] 未配置 doc.gateway.auth.secret（GATEWAY_JWT_SECRET），拒绝请求");
            throw new JwtException("gateway jwt secret not configured");
        }
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
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
