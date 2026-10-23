package com.example.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口级角色校验拦截器（design/11 §六-3 RBAC 简化版：服务 Controller 层落位）。
 *
 * <p>判定顺序：方法级 {@link RequireRoles} 优先于类级；未标注接口放行（业务 Controller
 * 须显式声明角色要求，公开/技术接口由装配边界隔离）。
 * 角色取自网关注入的 {@code X-User-Roles}（仅 dev demo 认证关闭时允许外部透传）：</p>
 * <ul>
 *   <li>携带角色且任一命中要求 → 放行；</li>
 *   <li>携带角色但均不满足 → HTTP 403（不泄漏要求明细，H-01）；</li>
 *   <li>未携带角色 → 按 {@code deny-unauthenticated} 开关（默认拒绝，仅 dev demo 显式放行）。</li>
 * </ul>
 *
 * <p>403 响应体沿用网关 401 的简单 JSON 形态（{@code {"code":403,...}}）；
 * 统一鉴权错误码待 PRD 12 回写后收口（B2 禁自造码段）。</p>
 */
public class RoleCheckInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RoleCheckInterceptor.class);

    private final RoleCheckProperties properties;

    public RoleCheckInterceptor(RoleCheckProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!properties.isEnabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRoles requirement = handlerMethod.getMethodAnnotation(RequireRoles.class);
        if (requirement == null) {
            requirement = handlerMethod.getBeanType().getAnnotation(RequireRoles.class);
        }
        if (requirement == null) {
            return true;
        }
        Set<String> owned = PlatformRoles.parse(
                request.getHeader(PlatformRoles.ROLES_HEADER));
        if (owned.isEmpty()) {
            if (properties.isDenyUnauthenticated()) {
                return forbidden(response, request, requirement);
            }
            // dev 口径：网关认证关闭、无角色注入时不拦截（生产建议开启 deny）
            return true;
        }
        if (PlatformRoles.hasAny(owned, requirement.value())) {
            return true;
        }
        return forbidden(response, request, requirement);
    }

    /** 403 响应写回（简单 JSON 体，与网关 401 形态一致） */
    private boolean forbidden(HttpServletResponse response, HttpServletRequest request,
                              RequireRoles requirement) throws Exception {
        log.warn("[role-check] 角色不足拒绝: path={}, method={}",
                request.getRequestURI(), request.getMethod());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":403,\"message\":\"权限不足，拒绝访问\"}");
        return false;
    }
}
