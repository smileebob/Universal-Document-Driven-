package com.example.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.config.GatewayAuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * AuthGlobalFilter 单元测试（阶段一出口修复补齐网关测试缺口）。
 *
 * <p>纯 Mock 方式（MockServerWebExchange），不依赖真实 Nacos/下游服务；
 * 覆盖矩阵：开关关闭放行、skip-paths 放行、无令牌 401、非 Bearer 401、
 * 非法令牌 401、secret 未配置拒绝、合法令牌透传 X-User-Id/X-User-Roles、
 * 剥离下游伪造同名头、roles 数组逗号连接。</p>
 */
class AuthGlobalFilterTest {

    /** HS256 测试密钥（≥32 字节；仅测试用途，非生产凭据） */
    private static final String SECRET = "unit-test-secret-key-for-gateway-0123456789";

    private GatewayAuthProperties properties;
    private AuthGlobalFilter filter;

    /** 记录过滤器链收到的（可能被 mutate 的）请求 */
    private ServerHttpRequest chainedRequest;

    @BeforeEach
    void setUp() {
        properties = new GatewayAuthProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        filter = new AuthGlobalFilter(properties);
        chainedRequest = null;
    }

    /** 执行过滤器并记录 chain 收到的请求 */
    private MockServerWebExchange doFilter(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ex -> {
            chainedRequest = ex.getRequest();
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return exchange;
    }

    /** 以测试密钥签发令牌 */
    private String sign(java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customizer) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject("u-1001")
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(key);
        customizer.accept(builder);
        return builder.compact();
    }

    @Test
    @DisplayName("认证开关关闭：直接放行，不校验令牌")
    void disabled_passThrough() {
        properties.setEnabled(false);
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x").build());
        assertThat(chainedRequest).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("skip-paths 命中（Ant 风格）：无令牌亦放行")
    void skipPath_passThrough() {
        properties.setSkipPaths(List.of("/actuator/**"));
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/actuator/health").build());
        assertThat(chainedRequest).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("无 Authorization 头：401 且 JSON 体含文案")
    void missingToken_401() {
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x").build());
        assertThat(chainedRequest).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("401").contains("缺少 Bearer 令牌");
    }

    @Test
    @DisplayName("Authorization 非 Bearer 前缀：401")
    void nonBearer_401() {
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x")
                        .header("Authorization", "Basic abc").build());
        assertThat(chainedRequest).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("非法令牌（签名不符）：401 且不泄漏细节")
    void invalidToken_401() {
        String tampered = sign(b -> { }) + "tampered";
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x")
                        .header("Authorization", "Bearer " + tampered).build());
        assertThat(chainedRequest).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("令牌无效或已过期");
    }

    @Test
    @DisplayName("secret 未配置：按拒绝处理（401），不放行")
    void secretNotConfigured_reject() {
        properties.setSecret("");
        String token = sign(b -> { });
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x")
                        .header("Authorization", "Bearer " + token).build());
        assertThat(chainedRequest).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("合法令牌：解析 subject/roles 透传 X-User-Id/X-User-Roles（数组逗号连接）")
    void validToken_propagateIdentityHeaders() {
        String token = sign(b -> b.claim("roles", List.of("admin", "finance")));
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x")
                        .header("Authorization", "Bearer " + token).build());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(chainedRequest).isNotNull();
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.USER_ID_HEADER))
                .isEqualTo("u-1001");
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.USER_ROLES_HEADER))
                .isEqualTo("admin,finance");
    }

    @Test
    @DisplayName("合法令牌（roles 为字符串）：原样透传")
    void validToken_stringRoles() {
        String token = sign(b -> b.claim("roles", "ops"));
        doFilter(MockServerHttpRequest.get("/api/v1/data/x")
                .header("Authorization", "Bearer " + token).build());
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.USER_ROLES_HEADER))
                .isEqualTo("ops");
    }

    @Test
    @DisplayName("合法令牌但无 roles 声明：不透传 X-User-Roles")
    void validToken_noRolesClaim() {
        String token = sign(b -> { });
        doFilter(MockServerHttpRequest.get("/api/v1/data/x")
                .header("Authorization", "Bearer " + token).build());
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.USER_ID_HEADER))
                .isEqualTo("u-1001");
        assertThat(chainedRequest.getHeaders().containsKey(AuthGlobalFilter.USER_ROLES_HEADER))
                .isFalse();
    }

    @Test
    @DisplayName("外部伪造 X-User-Id/X-User-Roles 头：剥离后以令牌解出值覆盖")
    void forgedHeaders_strippedAndOverridden() {
        String token = sign(b -> b.claim("roles", "user"));
        doFilter(MockServerHttpRequest.get("/api/v1/data/x")
                .header("Authorization", "Bearer " + token)
                .header(AuthGlobalFilter.USER_ID_HEADER, "forged-admin")
                .header(AuthGlobalFilter.USER_ROLES_HEADER, "forged-superadmin")
                .build());
        assertThat(chainedRequest.getHeaders().get(AuthGlobalFilter.USER_ID_HEADER))
                .containsExactly("u-1001");
        assertThat(chainedRequest.getHeaders().get(AuthGlobalFilter.USER_ROLES_HEADER))
                .containsExactly("user");
    }

    @Test
    @DisplayName("过滤器顺序：次于 TraceIdGlobalFilter（order=0）")
    void order() {
        assertThat(filter.getOrder()).isEqualTo(0);
    }

    @Test
    @DisplayName("信任边界（Minor-13）：入站 X-User-Dept 剥离，XFF 追加真实 remoteAddress（认证关闭分支亦生效）")
    void trustBoundary_deptStrippedAndXffAppended_whenDisabled() {
        properties.setEnabled(false);
        doFilter(MockServerHttpRequest.get("/api/v1/data/x")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.9", 34567))
                .header(AuthGlobalFilter.USER_DEPT_HEADER, "forged-dept")
                .header(AuthGlobalFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4")
                .build());
        assertThat(chainedRequest).isNotNull();
        assertThat(chainedRequest.getHeaders().containsKey(AuthGlobalFilter.USER_DEPT_HEADER)).isFalse();
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.X_FORWARDED_FOR_HEADER))
                .isEqualTo("1.2.3.4, 10.0.0.9");
    }

    @Test
    @DisplayName("信任边界（Minor-13）：无入站 XFF 时直接写真实 remoteAddress；认证分支同样生效")
    void trustBoundary_xffWritten_whenAbsent_validToken() {
        String token = sign(b -> { });
        doFilter(MockServerHttpRequest.get("/api/v1/data/x")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.9", 34567))
                .header("Authorization", "Bearer " + token)
                .header(AuthGlobalFilter.USER_DEPT_HEADER, "forged-dept")
                .build());
        assertThat(chainedRequest).isNotNull();
        assertThat(chainedRequest.getHeaders().containsKey(AuthGlobalFilter.USER_DEPT_HEADER)).isFalse();
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.X_FORWARDED_FOR_HEADER))
                .isEqualTo("10.0.0.9");
        assertThat(chainedRequest.getHeaders().getFirst(AuthGlobalFilter.USER_ID_HEADER))
                .isEqualTo("u-1001");
    }
}
