package com.example.gateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关认证配置（前缀 doc.gateway.auth，AuthGlobalFilter 绑定）。
 *
 * <p>放行规则从简、留扩展点：skip-paths 为 Ant 风格路径前缀列表，
 * 健康检查（/actuator/**）默认放行，其余按需经配置追加。</p>
 *
 * <p>身份边界：生产必须开启认证并注入长度 ≥ 32 字节的 HS256 密钥；
 * 仅当显式配置 issuer/audience 时额外校验归属。trusted-proxies 中的直连地址
 * 才允许读取入站 X-Forwarded-For 链，否则客户端 IP 一律取 TCP 对端。</p>
 */
@Component
@ConfigurationProperties(prefix = "doc.gateway.auth")
public class GatewayAuthProperties {

    /** 认证总开关（dev demo 可关闭，生产必须开启，见 GatewayAuthStartupGuard） */
    private boolean enabled = true;

    /** JWT 签名密钥（HS256），生产经 GATEWAY_JWT_SECRET 环境变量注入（R-05 零明文） */
    private String secret = "";

    /** 放行路径列表（Ant 风格，如 /actuator/**） */
    private List<String> skipPaths = new ArrayList<>();

    /** 期望 issuer（可空；配置后强制校验） */
    private String issuer = "";

    /** 期望 audience（可空；配置后强制校验） */
    private String audience = "";

    /** 可信反向代理/负载均衡地址（精确 IP；仅这些来源允许携带 X-Forwarded-For 链） */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * 仅 dev demo：认证关闭时保留前端透传的 X-User-Id/X-User-Roles，
     * 用于本地三角色演示。生产必须保持 false。
     */
    private boolean allowDemoIdentityHeaders = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public List<String> getSkipPaths() {
        return skipPaths;
    }

    public void setSkipPaths(List<String> skipPaths) {
        this.skipPaths = skipPaths;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public boolean isAllowDemoIdentityHeaders() {
        return allowDemoIdentityHeaders;
    }

    public void setAllowDemoIdentityHeaders(boolean allowDemoIdentityHeaders) {
        this.allowDemoIdentityHeaders = allowDemoIdentityHeaders;
    }
}
