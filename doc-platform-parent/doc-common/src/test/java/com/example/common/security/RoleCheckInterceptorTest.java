package com.example.common.security;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色校验拦截器单测（步骤 40：design/11 §六-3 服务 Controller 层鉴权落位）。
 *
 * <p>覆盖：方法级注解判定、角色命中放行、角色不足 403（JSON 体）、
 * 未携带角色按 deny-unauthenticated 开关分流、未标注接口放行、总开关关闭放行。</p>
 */
class RoleCheckInterceptorTest {

    /** 测试用控制器（方法级/类级注解样例） */
    static class SampleController {

        @RequireRoles(PlatformRoles.PLATFORM_ADMIN)
        public void platformOnly() {
        }

        @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
        public void configLevel() {
        }

        public void openEndpoint() {
        }
    }

    private final SampleController bean = new SampleController();

    private RoleCheckInterceptor interceptor(boolean denyUnauthenticated) {
        RoleCheckProperties properties = new RoleCheckProperties();
        properties.setDenyUnauthenticated(denyUnauthenticated);
        return new RoleCheckInterceptor(properties);
    }

    private HandlerMethod handler(String method) throws Exception {
        return new HandlerMethod(bean, SampleController.class.getMethod(method));
    }

    private MockHttpServletRequest request(String roles) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.setRequestURI("/api/v1/x");
        if (roles != null) {
            request.addHeader(PlatformRoles.ROLES_HEADER, roles);
        }
        return request;
    }

    @Test
    @DisplayName("未标注接口一律放行")
    void open_endpoint_passes() throws Exception {
        assertThat(interceptor(false).preHandle(
                request(null), new MockHttpServletResponse(), handler("openEndpoint"))).isTrue();
    }

    @Test
    @DisplayName("非 HandlerMethod 处理器直接放行（静态资源等）")
    void non_handler_method_passes() throws Exception {
        assertThat(interceptor(false).preHandle(
                request(null), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("角色命中（单角色/组合角色）放行")
    void matching_role_passes() throws Exception {
        assertThat(interceptor(false).preHandle(
                request("platform_admin"), new MockHttpServletResponse(),
                handler("platformOnly"))).isTrue();
        assertThat(interceptor(false).preHandle(
                request("business_admin"), new MockHttpServletResponse(),
                handler("configLevel"))).isTrue();
        assertThat(interceptor(false).preHandle(
                request("business_user,platform_admin"), new MockHttpServletResponse(),
                handler("platformOnly"))).isTrue();
    }

    @Test
    @DisplayName("携带角色但不满足 → 403 + JSON 体")
    void insufficient_role_forbidden() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = interceptor(false).preHandle(
                request("business_user"), response, handler("platformOnly"));
        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\":403")
                .contains("权限不足");
    }

    @Test
    @DisplayName("未携带角色头：默认拒绝；仅 dev demo 显式开关 false 时放行")
    void no_roles_header_follows_deny_switch() throws Exception {
        // 默认配置必须拒绝（SEC-01）：生产不得因令牌缺少 roles 而绕过角色校验
        RoleCheckProperties defaults = new RoleCheckProperties();
        assertThat(defaults.isDenyUnauthenticated()).isTrue();
        MockHttpServletResponse defaultResponse = new MockHttpServletResponse();
        assertThat(new RoleCheckInterceptor(defaults).preHandle(
                request(null), defaultResponse, handler("platformOnly"))).isFalse();
        assertThat(defaultResponse.getStatus()).isEqualTo(403);

        // dev demo 显式关闭拒绝（网关认证关闭场景）
        assertThat(interceptor(false).preHandle(
                request(null), new MockHttpServletResponse(), handler("platformOnly"))).isTrue();

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor(true).preHandle(
                request(null), response, handler("platformOnly"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("总开关关闭 → 全放行")
    void disabled_switch_passes_all() throws Exception {
        RoleCheckProperties properties = new RoleCheckProperties();
        properties.setEnabled(false);
        properties.setDenyUnauthenticated(true);
        RoleCheckInterceptor interceptor = new RoleCheckInterceptor(properties);
        assertThat(interceptor.preHandle(
                request("business_user"), new MockHttpServletResponse(),
                handler("platformOnly"))).isTrue();
    }

    @Test
    @DisplayName("角色头解析：逗号分隔 trim 去空去重")
    void parse_roles_header() {
        Set<String> roles = PlatformRoles.parse(" platform_admin , business_admin ,, business_admin");
        assertThat(roles).containsExactly("platform_admin", "business_admin");
        assertThat(PlatformRoles.parse(null)).isEmpty();
        assertThat(PlatformRoles.parse("  ")).isEmpty();
    }
}
