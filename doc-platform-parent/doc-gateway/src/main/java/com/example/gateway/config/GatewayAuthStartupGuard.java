package com.example.gateway.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产启动守卫：prod profile 下认证必须开启、密钥必须为 ≥32 字节的强密钥，
 * 且禁止保留 dev demo 身份头透传（SEC-01/03、ENV-02）。
 */
@Component
@Profile("prod")
public class GatewayAuthStartupGuard {

    private static final int MIN_SECRET_BYTES = 32;

    public GatewayAuthStartupGuard(GatewayAuthProperties properties) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "[gateway-auth] 启动拒绝：prod profile 下 doc.gateway.auth.enabled 必须为 true");
        }
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "[gateway-auth] 启动拒绝：prod profile 必须通过 GATEWAY_JWT_SECRET 注入"
                            + "至少 " + MIN_SECRET_BYTES + " 字节的 JWT 签名密钥");
        }
        if (properties.isAllowDemoIdentityHeaders()) {
            throw new IllegalStateException(
                    "[gateway-auth] 启动拒绝：prod profile 禁止 allow-demo-identity-headers=true");
        }
    }
}
