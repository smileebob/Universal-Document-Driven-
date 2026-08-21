package com.example.runtime.service.saga;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStore;

/**
 * Saga 崩溃恢复任务（任务清单 3：定时扫描 EXECUTING 超 5min 续推/转人工）。
 *
 * <p>扫描经 {@link SagaStore#scanStuckExecuting}（saga_execution 无索引，
 * getRange 全表扫描口径）；单实例内 AtomicBoolean 防重叠，多实例经
 * saga_execution 幂等与状态收敛天然串行化（续推仅重放幂等步骤）。
 * 线程池：messaging-bg-pool（G-01，任务在独立调度线程执行，此处仅保证
 * MDC traceId 注入）。</p>
 */
@Component
public class SagaRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryTask.class);

    private final SagaStore sagaStore;
    private final SagaOrchestrator orchestrator;
    private final SagaProperties properties;

    /** 重叠执行防护（上一轮未结束则跳过本轮） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SagaRecoveryTask(SagaStore sagaStore, SagaOrchestrator orchestrator,
                            SagaProperties properties) {
        this.sagaStore = sagaStore;
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    /**
     * 每 60s 扫描一轮滞留 EXECUTING（update_time ≤ now-5min）。
     */
    @Scheduled(fixedDelayString = "${doc.runtime.saga.recovery-interval-ms:60000}",
            initialDelayString = "${doc.runtime.saga.recovery-interval-ms:60000}")
    public void recover() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String traceId = "saga-recovery-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put(SagaProperties.MDC_TRACE_ID, traceId);
        try {
            long threshold = System.currentTimeMillis() - properties.getStuckThresholdMs();
            List<SagaExecution> stuck = sagaStore.scanStuckExecuting(threshold);
            if (stuck.isEmpty()) {
                return;
            }
            log.warn("[saga-recovery] 发现滞留 EXECUTING 记录: count={}", stuck.size());
            for (SagaExecution execution : stuck) {
                try {
                    orchestrator.resumeStuck(execution);
                } catch (RuntimeException e) {
                    log.error("[saga-recovery] 续推失败（下轮重试）: sagaId={}",
                            execution.getSagaId(), e);
                }
            }
        } finally {
            MDC.remove(SagaProperties.MDC_TRACE_ID);
            running.set(false);
        }
    }
}
