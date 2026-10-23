package com.example.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务端角色校验开关（doc.auth.role-check.*）。
 *
 * <ul>
 *   <li>{@code enabled}（默认 true）：总开关，关闭后拦截器全放行（仅限本地排障）；</li>
 *   <li>{@code denyUnauthenticated}（默认 true）：未携带 X-User-Roles 头时是否拒绝。
 *       生产默认拒绝；仅 dev demo（网关认证关闭、无角色注入）在外置配置中显式置 false，
 *       属于本地演示口径，不得带入生产。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "doc.auth.role-check")
public class RoleCheckProperties {

    private boolean enabled = true;

    private boolean denyUnauthenticated = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDenyUnauthenticated() {
        return denyUnauthenticated;
    }

    public void setDenyUnauthenticated(boolean denyUnauthenticated) {
        this.denyUnauthenticated = denyUnauthenticated;
    }
}
