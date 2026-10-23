package com.example.runtime.service.event;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocOperationLogStore;
import com.example.runtime.domain.event.DocProjectionStore;
import com.example.runtime.domain.model.DocOperationContext;

/**
 * 操作日志 → history/outbox/audit 投影器（03 §4.3）。
 *
 * <p>持久日志是恢复源：主行提交后的同步投影只用于降低延迟，失败由定时扫描
 * 续做；每个投影使用确定性主键幂等写入，全部成功后才推进 projection_status。
 * 单条失败只重试该条，源日志保留。</p>
 */
@Service
public class DocOperationProjector {

    private static final Logger log = LoggerFactory.getLogger(DocOperationProjector.class);

    /** 投影失败退避（与 outbox 同口径的 8 级） */
    static final long[] BACKOFF_MS = {
            5_000L, 15_000L, 45_000L, 120_000L, 300_000L, 600_000L, 1_800_000L,
            3_600_000L};

    private static final long LEASE_MS = 60_000L;

    private final DocOperationLogStore logStore;
    private final DocProjectionStore projectionStore;
    private final OutboxRelayService outboxRelayService;

    @Value("${doc.runtime.operation-log.projection-max-attempts:8}")
    private long maxAttempts = 8L;

    private final String instanceId = "projector-"
            + UUID.randomUUID().toString().substring(0, 8);

    private final AtomicBoolean running = new AtomicBoolean(false);

    public DocOperationProjector(DocOperationLogStore logStore,
                                 DocProjectionStore projectionStore,
                                 OutboxRelayService outboxRelayService) {
        this.logStore = logStore;
        this.projectionStore = projectionStore;
        this.outboxRelayService = outboxRelayService;
    }

    /**
     * 提交后同步投影（低延迟路径）。失败不抛业务异常，定时任务会续做。
     *
     * @return true=投影完成或此前已完成
     */
    public boolean projectNow(String docCode, String docUuid, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        String partitionKey = com.example.common.infrastructure.tablestore.util.PartitionKeys
                .dataKey(docCode, docUuid);
        String logUuid = "op:" + docUuid + ":" + operationId;
        DocOperationLog operationLog = logStore.find(partitionKey, logUuid).orElse(null);
        if (operationLog == null) {
            log.warn("[doc-projection] 日志行不存在，等待主事务可见性/人工核对: {}/{} op={}",
                    docCode, docUuid, operationId);
            return false;
        }
        if (operationLog.isProjectionDone()) {
            return true;
        }
        return projectOne(operationLog);
    }

    /** 定时扫描待投影日志（最终一致兜底）。 */
    @Scheduled(fixedDelayString = "${doc.runtime.operation-log.projection-interval-ms:1000}",
            initialDelayString = "${doc.runtime.operation-log.projection-initial-delay-ms:2000}")
    public void projectPending() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            String token = null;
            int total = 0;
            do {
                long now = System.currentTimeMillis();
                DocOperationLogStore.ScanPage page = logStore.scanPending(now, 100, token);
                for (DocOperationLog operationLog : page.logs()) {
                    try {
                        if (projectOne(operationLog)) {
                            total++;
                        }
                    } catch (RuntimeException e) {
                        log.warn("[doc-projection] 单条投影异常（下轮重试）: op={}, cause={}",
                                operationLog.getOperationId(), e.getMessage());
                    }
                }
                token = page.nextToken();
            } while (token != null && !token.isEmpty());
            if (total > 0) {
                log.info("[doc-projection] 本轮投影完成: count={}", total);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 重放指定单据时间窗内的日志。只读日志载荷并幂等重写投影，不修改日志。
     * 用于审计/历史修复与验证。
     *
     * @return 成功重放的日志条数
     */
    public int replay(String docCode, String docUuid, long fromTime, long toTime) {
        List<DocOperationLog> logs = logStore.scanByDoc(docCode, docUuid, fromTime, toTime, 100);
        int replayed = 0;
        for (DocOperationLog operationLog : logs) {
            try {
                projectPayloads(operationLog);
                replayed++;
            } catch (RuntimeException e) {
                log.error("[doc-projection] 重放失败: op={}, cause={}",
                        operationLog.getOperationId(), e.getMessage());
            }
        }
        return replayed;
    }

    /** 认领并投影单条；返回 false 表示被其他实例认领或条件已变化。 */
    private boolean projectOne(DocOperationLog operationLog) {
        long now = System.currentTimeMillis();
        if (!logStore.claim(operationLog, instanceId, now, now + LEASE_MS)) {
            return false;
        }
        try {
            projectPayloads(operationLog);
            logStore.markProjected(operationLog, instanceId);
            if (operationLog.hasEvent()) {
                outboxRelayService.enqueueDoc(operationLog.getPartitionKey(),
                        operationLog.getDocUuid());
            }
            log.debug("[doc-projection] 投影完成: op={}, doc={}/{}",
                    operationLog.getOperationId(), operationLog.getDocCode(),
                    operationLog.getDocUuid());
            return true;
        } catch (DocProjectionStore.ProjectionConflictException e) {
            log.error("[doc-projection][ALERT] 不可变投影冲突，转人工: op={}, cause={}",
                    operationLog.getOperationId(), e.getMessage());
            logStore.markProjectionFailure(operationLog, instanceId, e.getMessage(),
                    now + BACKOFF_MS[BACKOFF_MS.length - 1], true);
            return false;
        } catch (RuntimeException e) {
            long attempts = operationLog.getProjectionAttempts() + 1;
            boolean manual = attempts >= maxAttempts;
            long backoff = BACKOFF_MS[(int) Math.min(attempts - 1, BACKOFF_MS.length - 1)];
            log.warn("[doc-projection] 投影失败{}: op={}, attempt={}, nextInMs={}, cause={}",
                    manual ? "（转人工）" : "", operationLog.getOperationId(),
                    attempts, backoff, e.getMessage());
            logStore.markProjectionFailure(operationLog, instanceId, e.getMessage(),
                    now + backoff, manual);
            return false;
        }
    }

    /** 三类投影幂等写入（重放路径不推进状态）。 */
    private void projectPayloads(DocOperationLog operationLog) {
        if (DocOperationContext.OP_CREATE.equals(operationLog.getOperation())
                || DocOperationContext.OP_TRANSITION.equals(operationLog.getOperation())) {
            projectionStore.putHistoryIfAbsent(operationLog);
        }
        if (operationLog.hasEvent()) {
            projectionStore.putOutboxIfAbsent(operationLog);
        }
        projectionStore.putAuditIfAbsent(operationLog);
    }
}
