package com.example.runtime.domain.saga;

import java.util.List;

/**
 * 事务步骤编排计划（tx_config 按流转匹配后的运行域模型，TX-01，A-04 防腐：
 * domain 不触碰 meta-service-api 契约 DTO）。
 *
 * <p>steps 按 seq 升序正向执行、逆序冲正（design/05 §2.5）。</p>
 */
public record TxPlan(String docCode, long fromStatus, long toStatus, List<TxStep> steps) {

    /**
     * 单个事务步骤（prd/04 TX-01 字段集运行域投影）。
     *
     * @param seq           步骤序号（1 起）
     * @param name          步骤名（SAP/APS/通知）
     * @param actionUrl     正向执行端点
     * @param compensateUrl 冲正端点（可空=无冲正动作）
     * @param timeoutMs     超时毫秒（meta 侧已归一默认 10000）
     * @param retry         连接类异常重试次数（meta 侧已归一默认 0，封顶 3）
     */
    public record TxStep(int seq, String name, String actionUrl, String compensateUrl,
                         long timeoutMs, int retry) {
    }
}
