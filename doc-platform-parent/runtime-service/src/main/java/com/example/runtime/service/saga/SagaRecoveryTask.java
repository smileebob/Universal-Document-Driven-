package com.example.runtime.service.saga;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.port.DocDataStore;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.SagaStore;

/**
 * Saga 崩溃恢复任务（TX-03）：
 * <ul>
 *   <li>EXECUTING 超阈值：续推剩余外部步骤；</li>
 *   <li>ACTIONS_SUCCEEDED 超阈值：外部步骤已完成但本地提交结果未知。
 *       单据已到目标状态 → 标记 COMPLETED；仍为起始状态且版本等于快照版本
 *       → 幂等提交本地事务；其余情况 → 逆序冲正外部步骤（失败转死信）。</li>
 * </ul>
 *
 * <p>saga_execution 无索引，按 getRange 全表扫描（行数百级）；单实例内
 * AtomicBoolean 防重叠。多实例通过单据版本 CAS、提交后二次读状态与
 * 幂等冲正收敛：提交竞争失败方若发现单据已到目标状态，只补收口不冲正。</p>
 */
@Component
public class SagaRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryTask.class);

    private final SagaStore sagaStore;
    private final SagaOrchestrator orchestrator;
    private final SagaProperties properties;
    private final DocDataStore docDataStore;

    /** 重叠执行防护（上一轮未结束则跳过本轮） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SagaRecoveryTask(SagaStore sagaStore, SagaOrchestrator orchestrator,
                            SagaProperties properties, DocDataStore docDataStore) {
        this.sagaStore = sagaStore;
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.docDataStore = docDataStore;
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
            Set<String> processed = new HashSet<>();
            List<SagaExecution> executing = sagaStore.scanStuckExecuting(threshold);
            if (!executing.isEmpty()) {
                log.warn("[saga-recovery] 发现滞留 EXECUTING 记录: count={}", executing.size());
            }
            for (SagaExecution execution : executing) {
                try {
                    orchestrator.resumeStuck(execution);
                    SagaExecution latest = sagaStore.find(execution.getSagaId()).orElse(null);
                    if (latest != null && latest.getStatus() == SagaStatus.ACTIONS_SUCCEEDED) {
                        recoverActionsSucceeded(latest);
                        processed.add(latest.getSagaId());
                    }
                } catch (RuntimeException e) {
                    log.error("[saga-recovery] 续推失败（下轮重试）: sagaId={}",
                            execution.getSagaId(), e);
                }
            }

            List<SagaExecution> pendingCommit = sagaStore.scanActionsSucceeded(threshold);
            if (!pendingCommit.isEmpty()) {
                log.warn("[saga-recovery] 发现待收口 ACTIONS_SUCCEEDED 记录: count={}",
                        pendingCommit.size());
            }
            for (SagaExecution execution : pendingCommit) {
                if (!processed.add(execution.getSagaId())) {
                    continue;
                }
                try {
                    recoverActionsSucceeded(execution);
                } catch (RuntimeException e) {
                    log.error("[saga-recovery] 待提交 Saga 收口失败（下轮重试）: sagaId={}",
                            execution.getSagaId(), e);
                }
            }
        } finally {
            MDC.remove(SagaProperties.MDC_TRACE_ID);
            running.set(false);
        }
    }

    /**
     * 收口 ACTIONS_SUCCEEDED：根据单据当前状态/版本决定补提交、标记完成或冲正。
     */
    private void recoverActionsSucceeded(SagaExecution execution) {
        String sagaId = execution.getSagaId();
        Optional<DocDataRecord> found = docDataStore.find(
                execution.getDocCode(), execution.getDocUuid());
        if (found.isEmpty()) {
            compensate(execution, "单据不存在或已删除");
            return;
        }
        DocDataRecord record = found.get();
        if (record.getCurrentStatus() == execution.getToStatus()) {
            orchestrator.markCommitted(sagaId);
            log.info("[saga-recovery] 单据已到目标状态，补标记完成: sagaId={}", sagaId);
            return;
        }
        if (record.getCurrentStatus() == execution.getFromStatus()
                && record.getVersion() == execution.getExpectedVersion()) {
            try {
                docDataStore.commitTransition(execution.getDocCode(), execution.getDocUuid(),
                        execution.getExpectedVersion(), execution.getFromStatus(),
                        execution.getToStatus(), execution.getOperator(),
                        "saga-recovery", sagaId);
                orchestrator.markCommitted(sagaId);
                log.info("[saga-recovery] 待提交 Saga 已完成本地提交: sagaId={}", sagaId);
            } catch (RuntimeException e) {
                // 多实例竞争：另一恢复实例可能已提交成功，先二次确认再决定是否冲正
                Optional<DocDataRecord> latest = docDataStore.find(
                        execution.getDocCode(), execution.getDocUuid());
                if (latest.isPresent()
                        && latest.get().getCurrentStatus() == execution.getToStatus()) {
                    orchestrator.markCommitted(sagaId);
                    log.info("[saga-recovery] 提交竞争后发现已提交，补标记完成: sagaId={}", sagaId);
                } else {
                    log.error("[saga-recovery] 本地提交失败，执行外部步骤冲正: sagaId={}", sagaId, e);
                    compensate(execution, "恢复提交失败: " + e.getMessage());
                }
            }
            return;
        }
        compensate(execution, "单据状态/版本已变化，不能安全提交");
    }

    /** 冲正外部步骤；失败/未知显式告警，不假装已完成 */
    private void compensate(SagaExecution execution, String reason) {
        String sagaId = execution.getSagaId();
        try {
            SagaStatus status = orchestrator.compensateAfterCommitFailure(sagaId);
            if (status == SagaStatus.DEAD) {
                log.error("[saga-recovery][ALERT] 待提交 Saga 冲正失败转死信，需人工处置: "
                        + "sagaId={}, reason={}", sagaId, reason);
            } else if (status == SagaStatus.COMPENSATED) {
                log.warn("[saga-recovery] 待提交 Saga 已冲正: sagaId={}, reason={}",
                        sagaId, reason);
            } else {
                log.error("[saga-recovery] 待提交 Saga 冲正状态未知，需人工核对: "
                        + "sagaId={}, status={}, reason={}", sagaId, status, reason);
            }
        } catch (RuntimeException e) {
            log.error("[saga-recovery][ALERT] 待提交 Saga 冲正异常，需人工核对: sagaId={}, reason={}",
                    sagaId, reason, e);
        }
    }
}
