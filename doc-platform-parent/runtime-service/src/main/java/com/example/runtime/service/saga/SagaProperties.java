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

    /** 单 Saga 总预算毫秒（正向+冲正合计，超出即失败转冲正/死信） */
    private long totalBudgetMs = 60_000L;

    /** EXECUTING 滞留判定阈值毫秒（崩溃恢复扫描口径） */
    private long stuckThresholdMs = 300_000L;

    /** 崩溃恢复定时任务开关 */
    private boolean recoveryEnabled = true;

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
