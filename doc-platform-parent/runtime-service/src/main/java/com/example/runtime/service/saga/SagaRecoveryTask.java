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

import com.example.common.constants.DocMqConstants;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocOperationContext;
import com.example.runtime.domain.port.DocDataStore;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.SagaStore;
import com.example.runtime.service.event.DocOperationProjector;

/**
 * Saga 崩溃恢复任务（TX-03/TX-04）。
 *
 * <ul>
 *   <li>EXECUTING：续推剩余步骤；最近一步结果未知时转补偿（不重复执行）；</li>
 *   <li>ACTIONS_SUCCEEDED / COMMIT_PENDING：按单据版本/状态补提交或冲正；</li>
 *   <li>COMPENSATING：继续补偿；超时/未知转 MANUAL_REQUIRED，不假装回滚；</li>
 *   <li>多实例：先以 status + lease_owner + update_time CAS 认领租约，再推进。</li>
 * </ul>
 */
@Component
public class SagaRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryTask.class);

    private final SagaStore sagaStore;
    private final SagaOrchestrator orchestrator;
    private final SagaProperties properties;
    private final DocDataStore docDataStore;
    private final DocOperationProjector projector;

    private final String instanceId = "saga-recovery-"
            + UUID.randomUUID().toString().substring(0, 8);

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SagaRecoveryTask(SagaStore sagaStore, SagaOrchestrator orchestrator,
                            SagaProperties properties, DocDataStore docDataStore,
                            DocOperationProjector projector) {
        this.sagaStore = sagaStore;
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.docDataStore = docDataStore;
        this.projector = projector;
    }

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
            List<SagaExecution> candidates = sagaStore.scanNonTerminal(threshold);
            if (!candidates.isEmpty()) {
                log.warn("[saga-recovery] 发现非终态记录: count={}", candidates.size());
            }
            for (SagaExecution candidate : candidates) {
                if (!processed.add(candidate.getSagaId())) {
                    continue;
                }
                long now = System.currentTimeMillis();
                if (candidate.getLeaseUntil() > now
                        && candidate.getLeaseOwner() != null
                        && !candidate.getLeaseOwner().isBlank()) {
                    continue;
                }
                if (!sagaStore.claim(candidate, instanceId,
                        now + properties.getLeaseMs(), candidate.getUpdateTime())) {
                    continue;
                }
                try {
                    recoverOne(candidate, processed);
                } catch (RuntimeException e) {
                    log.error("[saga-recovery] 恢复失败（下轮重试）: sagaId={}",
                            candidate.getSagaId(), e);
                } finally {
                    releaseLease(candidate.getSagaId());
                }
            }
        } finally {
            MDC.remove(SagaProperties.MDC_TRACE_ID);
            running.set(false);
        }
    }

    private void recoverOne(SagaExecution claimed, Set<String> processed) {
        SagaExecution execution = sagaStore.find(claimed.getSagaId()).orElse(claimed);
        SagaStatus status = execution.getStatus();
        if (status == SagaStatus.EXECUTING) {
            orchestrator.resumeStuck(execution);
        } else if (status == SagaStatus.COMPENSATING) {
            orchestrator.resumeCompensation(execution);
        } else if (status == SagaStatus.ACTIONS_SUCCEEDED
                || status == SagaStatus.COMMIT_PENDING) {
            recoverActionsSucceeded(execution);
            return;
        }
        SagaExecution latest = sagaStore.find(execution.getSagaId()).orElse(execution);
        if ((latest.getStatus() == SagaStatus.ACTIONS_SUCCEEDED
                || latest.getStatus() == SagaStatus.COMMIT_PENDING)
                && processed.add(latest.getSagaId() + ":commit")) {
            recoverActionsSucceeded(latest);
        }
    }

    /**
     * 收口 ACTIONS_SUCCEEDED / COMMIT_PENDING：根据单据当前状态/版本决定补提交、
     * 标记完成或冲正。
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
            long occurredAt = System.currentTimeMillis();
            DocOperationContext context = new DocOperationContext(sagaId,
                    DocOperationContext.OP_TRANSITION, execution.getOperator(),
                    execution.getTraceId(), sagaId, "saga-recovery",
                    DocMqConstants.EventTypes.DOC_STATUS_CHANGED, 0L,
                    execution.getExpectedVersion(), execution.getFromStatus(),
                    record.getAttributes(), execution.getExpectedVersion() + 1,
                    execution.getToStatus(), record.getAttributes(),
                    record.getRelations(), false, occurredAt);
            try {
                docDataStore.commitTransition(execution.getDocCode(), execution.getDocUuid(),
                        execution.getExpectedVersion(), execution.getFromStatus(),
                        execution.getToStatus(), context);
                projector.projectNow(execution.getDocCode(), execution.getDocUuid(), sagaId);
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
            if (status == SagaStatus.COMPENSATED) {
                docDataStore.releaseOperation(execution.getDocCode(), execution.getDocUuid(),
                        sagaId, System.currentTimeMillis());
                log.warn("[saga-recovery] 待提交 Saga 已冲正并释放在途锁: sagaId={}, reason={}",
                        sagaId, reason);
            } else if (status == SagaStatus.DEAD || status == SagaStatus.MANUAL_REQUIRED) {
                log.error("[saga-recovery][ALERT] 待提交 Saga 冲正失败/未知转人工"
                        + "（保留在途锁）: sagaId={}, status={}, reason={}",
                        sagaId, status, reason);
            } else {
                log.error("[saga-recovery] 待提交 Saga 冲正状态未收敛，需人工核对: "
                        + "sagaId={}, status={}, reason={}", sagaId, status, reason);
            }
        } catch (RuntimeException e) {
            log.error("[saga-recovery][ALERT] 待提交 Saga 冲正异常，需人工核对: sagaId={}, reason={}",
                    sagaId, reason, e);
        }
    }

    /** 释放恢复租约（不改变业务状态）。 */
    private void releaseLease(String sagaId) {
        try {
            sagaStore.releaseLease(sagaId, instanceId, System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("[saga-recovery] 租约释放失败（到期自动失效）: sagaId={}", sagaId, e);
        }
    }
}
