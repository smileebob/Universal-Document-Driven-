package com.example.common.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 平台角色常量与解析工具（prd/01 §2.1 权限矩阵三类角色）。
 *
 * <p>角色来源：网关注入的 {@code X-User-Roles} 头（逗号分隔多角色，
 * AuthGlobalFilter 认证后透传；dev 关闭认证时外部头原样透传）。
 * 服务端接口级鉴权（design/11 §六-3 RBAC 简化版）以此为准。</p>
 */
public final class PlatformRoles {

    /** 平台管理员：全部功能（含事务配置/补偿/事件重发/对账等运营接口） */
    public static final String PLATFORM_ADMIN = "platform_admin";

    /** 业务管理员（配置管理员）：定义/状态机/规则配置可写，事务配置与运营接口不可达 */
    public static final String BUSINESS_ADMIN = "business_admin";

    /** 业务用户：业务数据与单据操作，配置类接口不可达 */
    public static final String BUSINESS_USER = "business_user";

    /** 管理员组合（平台管理员 + 业务管理员）：配置类接口的最低门槛 */
    public static final String[] ADMIN_AND_CONFIG = {PLATFORM_ADMIN, BUSINESS_ADMIN};

    /** 下游透传头名称（与网关 AuthGlobalFilter 一致） */
    public static final String ROLES_HEADER = "X-User-Roles";

    private PlatformRoles() {
    }

    /**
     * 解析角色头为集合（逗号分隔，trim + 去空 + 去重，保持顺序）。
     *
     * @param header X-User-Roles 头值（null/空白返回空集）
     * @return 角色集合
     */
    public static Set<String> parse(String header) {
        if (header == null || header.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> roles = new LinkedHashSet<>();
        Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .forEach(roles::add);
        return roles;
    }

    /**
     * 是否具备任一要求角色。
     *
     * @param owned    实际持有角色集
     * @param required 要求角色（任一命中即通过）
     * @return true=放行
     */
    public static boolean hasAny(Set<String> owned, String... required) {
        for (String role : required) {
            if (owned.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
