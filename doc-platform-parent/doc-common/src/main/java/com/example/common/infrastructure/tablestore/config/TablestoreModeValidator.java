package com.example.common.infrastructure.tablestore.config;

import java.util.Arrays;

import org.springframework.core.env.Environment;

/**
 * Tablestore 装配模式 fail-fast 校验器（阶段一出口修复引入）。
 *
 * <p>构造即校验：mode 缺省（按 OTS 语义）且 endpoint/instance 未配置齐全时，
 * 立即抛出 {@link IllegalStateException} 阻断启动并给出清晰处置指引——
 * 避免下游因缺 {@code TablestoreTemplate} Bean 抛出难以定位的 NoSuchBeanDefinitionException。</p>
 *
 * <p>生产附加约束（ENV-02）：prod profile 下禁止 simulation、禁止启动自动建表；
 * 表/索引初始化必须由受控发布流程执行。</p>
 */
public final class TablestoreModeValidator {

    private static final String PROD_PROFILE = "prod";

    public TablestoreModeValidator(TablestoreProperties properties, Environment environment) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
        if (prod && properties.resolvedMode() == TablestoreProperties.Mode.SIMULATION) {
            throw new IllegalStateException(
                    "[tablestore] 启动拒绝：prod profile 禁止 doc.tablestore.mode=simulation");
        }
        if (prod && properties.getBootstrap().isAutoCreate()) {
            throw new IllegalStateException(
                    "[tablestore] 启动拒绝：prod profile 禁止 bootstrap.auto-create=true，"
                            + "表与索引初始化必须由受控发布流程执行（TS-06）");
        }
        if (properties.resolvedMode() == TablestoreProperties.Mode.OTS && !properties.isConfigured()) {
            throw new IllegalStateException(
                    "[tablestore] 装配失败：doc.tablestore.mode 未设置为 simulation，"
                            + "且 doc.tablestore.endpoint/instance 未配置齐全，无法装配任何 Tablestore 客户端。"
                            + "处置：生产环境经环境变量注入 OTS_ENDPOINT/OTS_INSTANCE/OTS_ACCESS_KEY_ID/OTS_ACCESS_KEY_SECRET；"
                            + "dev/test/sim 环境设置 doc.tablestore.mode=simulation"
                            + "（该模式仅在 spring profile 含 dev/test/sim 时允许）。");
        }
    }
}
