package com.example.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务端角色校验开关（doc.auth.role-check.*）。
 *
 * <ul>
 *   <li>{@code enabled}（默认 true）：总开关，关闭后拦截器全放行（仅限排障）；</li>
 *   <li>{@code denyUnauthenticated}（默认 false）：未携带 X-User-Roles 头时是否拒绝。
 *       dev 网关认证关闭（无角色注入）保持 false；生产网关开启认证后建议 true。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "doc.auth.role-check")
public class RoleCheckProperties {

    private boolean enabled = true;

    private boolean denyUnauthenticated = false;

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
