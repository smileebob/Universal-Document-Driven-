package com.example.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 生产启动守卫：认证必须开启、密钥强度达标、禁止演示身份头（SEC-01/03）。 */
class GatewayAuthStartupGuardTest {

    private static GatewayAuthProperties properties(boolean enabled, String secret) {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setEnabled(enabled);
        properties.setSecret(secret);
        return properties;
    }

    @Test
    @DisplayName("合法生产配置通过")
    void allowsStrongConfig() {
        GatewayAuthProperties properties = properties(true,
                "production-strength-secret-key-0123456789");
        assertThatCode(() -> new GatewayAuthStartupGuard(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("关闭认证拒绝")
    void rejectsDisabledAuth() {
        GatewayAuthProperties properties = properties(false,
                "production-strength-secret-key-0123456789");
        assertThatThrownBy(() -> new GatewayAuthStartupGuard(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enabled");
    }

    @Test
    @DisplayName("弱密钥/空密钥拒绝")
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new GatewayAuthStartupGuard(properties(true, "short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
        assertThatThrownBy(() -> new GatewayAuthStartupGuard(properties(true, "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("演示身份头透传在生产禁止")
    void rejectsDemoIdentityHeaders() {
        GatewayAuthProperties properties = properties(true,
                "production-strength-secret-key-0123456789");
        properties.setAllowDemoIdentityHeaders(true);
        assertThatThrownBy(() -> new GatewayAuthStartupGuard(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }
}
