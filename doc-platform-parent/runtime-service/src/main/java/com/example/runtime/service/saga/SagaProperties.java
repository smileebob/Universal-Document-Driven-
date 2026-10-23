package com.example.runtime.service.saga;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Saga 编排器可调参数（doc.runtime.saga.*）。
 *
 * <ul>
 *   <li>total-budget-ms：单 Saga 总预算（任务清单 3 ≤60s，缺省 60000）；</li>
 *   <li>stuck-threshold-ms：EXECUTING 滞留判定阈值（任务清单 3：5min）；</li>
 *   <li>recovery-enabled：崩溃恢复定时任务开关（缺省 true）。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "doc.runtime.saga")
public class SagaProperties {

    /** MDC traceId 键（日志规范统一） */
    public static final String MDC_TRACE_ID = "traceId";

    /** 正向执行预算毫秒（与补偿预算分离） */
    private long forwardBudgetMs = 60_000L;

    /** 补偿执行预算毫秒（独立计时，不复用正向剩余预算） */
    private long compensationBudgetMs = 60_000L;

    /** 崩溃恢复单次续推预算毫秒（新的尝试预算，不倒扣历史耗时） */
    private long recoveryAttemptBudgetMs = 60_000L;

    /** 恢复/收口租约毫秒（防多实例同时推进同一 Saga） */
    private long leaseMs = 120_000L;

    /** 兼容旧配置：未显式设置 forwardBudgetMs 时作为正向预算 */
    private long totalBudgetMs = 60_000L;

    /** EXECUTING 滞留判定阈值毫秒（崩溃恢复扫描口径） */
    private long stuckThresholdMs = 300_000L;

    /** 崩溃恢复定时任务开关 */
    private boolean recoveryEnabled = true;

    public long getForwardBudgetMs() {
        return forwardBudgetMs > 0 ? forwardBudgetMs : totalBudgetMs;
    }

    public void setForwardBudgetMs(long forwardBudgetMs) {
        this.forwardBudgetMs = forwardBudgetMs;
    }

    public long getCompensationBudgetMs() {
        return compensationBudgetMs;
    }

    public void setCompensationBudgetMs(long compensationBudgetMs) {
        this.compensationBudgetMs = compensationBudgetMs;
    }

    public long getRecoveryAttemptBudgetMs() {
        return recoveryAttemptBudgetMs;
    }

    public void setRecoveryAttemptBudgetMs(long recoveryAttemptBudgetMs) {
        this.recoveryAttemptBudgetMs = recoveryAttemptBudgetMs;
    }

    public long getLeaseMs() {
        return leaseMs;
    }

    public void setLeaseMs(long leaseMs) {
        this.leaseMs = leaseMs;
    }

    public long getTotalBudgetMs() {
        return totalBudgetMs;
    }

    public void setTotalBudgetMs(long totalBudgetMs) {
        this.totalBudgetMs = totalBudgetMs;
    }

    public long getStuckThresholdMs() {
        return stuckThresholdMs;
    }

    public void setStuckThresholdMs(long stuckThresholdMs) {
        this.stuckThresholdMs = stuckThresholdMs;
    }

    public boolean isRecoveryEnabled() {
        return recoveryEnabled;
    }

    public void setRecoveryEnabled(boolean recoveryEnabled) {
        this.recoveryEnabled = recoveryEnabled;
    }

    /** 当前 MDC traceId 快照（无则空串） */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_TRACE_ID);
        return traceId == null ? "" : traceId;
    }
}
