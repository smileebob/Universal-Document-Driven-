package com.example.common.infrastructure.tablestore.config;

/**
 * Tablestore 装配模式 fail-fast 校验器（阶段一出口修复引入）。
 *
 * <p>构造即校验：mode 缺省（按 OTS 语义）且 endpoint/instance 未配置齐全时，
 * 立即抛出 {@link IllegalStateException} 阻断启动并给出清晰处置指引——
 * 避免下游因缺 {@code TablestoreTemplate} Bean 抛出难以定位的 NoSuchBeanDefinitionException。</p>
 *
 * <p>mode=simulation 时本校验放行，profile 安全防护由 {@link SimulationModeGuard} 承担。</p>
 */
public final class TablestoreModeValidator {

    public TablestoreModeValidator(TablestoreProperties properties) {
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
