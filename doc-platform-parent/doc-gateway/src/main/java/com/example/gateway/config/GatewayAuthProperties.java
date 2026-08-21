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
 */
@Component
@ConfigurationProperties(prefix = "doc.gateway.auth")
public class GatewayAuthProperties {

    /** 认证总开关（dev 联调可关闭，生产必须开启） */
    private boolean enabled = true;

    /** JWT 签名密钥（HS256），生产经 GATEWAY_JWT_SECRET 环境变量注入（R-05 零明文） */
    private String secret = "";

    /** 放行路径列表（Ant 风格，如 /actuator/**） */
    private List<String> skipPaths = new ArrayList<>();

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
}
