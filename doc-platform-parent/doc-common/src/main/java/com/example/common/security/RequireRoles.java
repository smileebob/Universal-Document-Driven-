package com.example.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级角色要求（design/11 §六-3：鉴权判定落位服务 Controller 层）。
 *
 * <p>标注于 Controller 方法或类：方法级优先于类级。{@code value} 为任一命中
 * 即放行的角色集合（如 {@link PlatformRoles#ADMIN_AND_CONFIG}）。</p>
 *
 * <p>拦截判定由 {@link RoleCheckInterceptor} 执行：
 * 携带角色但均不满足 → HTTP 403；未携带角色头时按
 * {@code doc.auth.role-check.deny-unauthenticated} 开关决定放行/拒绝
 * （dev 网关认证关闭、无角色注入场景默认放行，生产网关开启认证后建议置 true）。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRoles {

    /**
     * 允许访问的角色（任一命中即放行）。
     *
     * @return 角色数组
     */
    String[] value();
}
