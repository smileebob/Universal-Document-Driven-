package com.example.common.infrastructure.tablestore.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;

/**
 * simulation 模式 profile 安全防护（阶段一出口修复引入）。
 *
 * <p>{@code doc.tablestore.mode=simulation} 仅允许在 spring profile 含
 * dev/test/sim 之一、且不包含 prod 时装配；生产 profile（含 prod 与其他 profile
 * 混用、无激活 profile 的缺省环境）下构造即抛出 {@link IllegalStateException}
 * 显式拒绝并启动失败，防止仿真模式被误用于生产（内存存储无持久化，数据即启即失）。</p>
 */
public final class SimulationModeGuard {

    /** 允许 simulation 模式的 profile 白名单 */
    private static final Set<String> ALLOWED_PROFILES = Set.of("dev", "test", "sim");

    /** 生产标识（出现即优先拒绝，任何混合激活都不放行） */
    private static final String PROD_PROFILE = "prod";

    public SimulationModeGuard(Environment environment) {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean prod = activeProfiles.contains(PROD_PROFILE);
        boolean allowed = !prod && activeProfiles.stream().anyMatch(ALLOWED_PROFILES::contains);
        if (!allowed) {
            throw new IllegalStateException(
                    "[tablestore] 启动拒绝：doc.tablestore.mode=simulation 仅在 spring profile 含 "
                            + ALLOWED_PROFILES + " 之一且不含 " + PROD_PROFILE + " 时允许；当前激活 profiles="
                            + activeProfiles
                            + "。生产环境禁止仿真模式，请改用 mode=ots 并配置 OTS 接入参数。");
        }
    }
}
