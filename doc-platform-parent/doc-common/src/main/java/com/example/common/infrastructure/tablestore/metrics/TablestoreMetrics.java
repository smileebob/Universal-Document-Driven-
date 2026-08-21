package com.example.common.infrastructure.tablestore.metrics;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Tablestore 防腐层操作指标（design/11 §4.2：Tablestore SDK 请求延迟/错误计数经
 * TablestoreTemplate 打点，J-03）。
 *
 * <p>指标名与维度：</p>
 * <ul>
 *   <li>{@code ddp_tablestore_operation_seconds}（Timer 直方图，含 count/sum/max 与
 *       P95/P99 百分位）——维度 {@code operation}（操作类型）；</li>
 *   <li>{@code ddp_tablestore_operation_total}（Counter）——维度
 *       {@code operation} + {@code result}（success/error）。</li>
 * </ul>
 *
 * <p>未装配 MeterRegistry 时（如单元测试直接 new 仿真实现）为空实现，不打点。</p>
 */
public final class TablestoreMetrics {

    /** 无 MeterRegistry 时的空实现（NOOP） */
    public static final TablestoreMetrics NOOP = new TablestoreMetrics(null);

    private final MeterRegistry registry;

    public TablestoreMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public boolean isEnabled() {
        return registry != null;
    }

    /**
     * 记录一次操作耗时与结果。
     *
     * @param operation 操作类型（get_row/batch_get/put_row/update_row/delete_row/
     *                  batch_write/get_range/get_partition_range/search/local_transaction）
     * @param startNanos 起始 {@link System#nanoTime()}
     * @param success   true=成功；false=异常（异常仍计入延迟分布，result=error）
     */
    public void record(String operation, long startNanos, boolean success) {
        if (registry == null) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        Timer.builder("ddp_tablestore_operation_seconds")
                .tag("operation", operation)
                .publishPercentiles(0.95, 0.99)
                .register(registry)
                .record(elapsed, TimeUnit.NANOSECONDS);
        Counter.builder("ddp_tablestore_operation_total")
                .tag("operation", operation)
                .tag("result", success ? "success" : "error")
                .register(registry)
                .increment();
    }
}
