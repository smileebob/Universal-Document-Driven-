package com.example.runtime.service.event;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocOperationLogStore;

/**
 * 已投影操作日志清理（03 §4.3：所有投影确认后才允许清理源日志）。
 *
 * <p>开关默认关闭；默认 dry-run。仅删除 projection_status=1 且超过保留期的
 * 日志行，历史/outbox/审计已持久化，不受影响。</p>
 */
@Component
public class DocOperationLogCleanupTask {

    static final long RETENTION_MS = 7L * 24 * 3600 * 1000;

    private static final Logger log = LoggerFactory.getLogger(DocOperationLogCleanupTask.class);

    private final DocOperationLogStore logStore;

    @Value("${doc.runtime.operation-log.cleanup-enabled:false}")
    private boolean cleanupEnabled;

    @Value("${doc.runtime.operation-log.cleanup-dry-run:true}")
    private boolean dryRun;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public DocOperationLogCleanupTask(DocOperationLogStore logStore) {
        this.logStore = logStore;
    }

    @Scheduled(fixedDelayString = "${doc.runtime.operation-log.cleanup-interval-ms:3600000}",
            initialDelayString = "${doc.runtime.operation-log.cleanup-interval-ms:3600000}")
    public void cleanup() {
        if (!cleanupEnabled || !running.compareAndSet(false, true)) {
            return;
        }
        MDC.put("traceId", "doc-log-cleanup-"
                + java.util.UUID.randomUUID().toString().substring(0, 8));
        try {
            long before = System.currentTimeMillis() - RETENTION_MS;
            int processed = 0;
            int deleted = 0;
            String token = null;
            do {
                DocOperationLogStore.ScanPage page = logStore.scanProjected(before, 100, token);
                for (DocOperationLog operationLog : page.logs()) {
                    processed++;
                    if (!dryRun) {
                        try {
                            if (logStore.deleteProjected(operationLog)) {
                                deleted++;
                            }
                        } catch (RuntimeException e) {
                            log.warn("[doc-log-cleanup] 删除失败（下轮重试）: op={}",
                                    operationLog.getOperationId());
                        }
                    }
                    if (processed >= 1000) {
                        break;
                    }
                }
                token = processed >= 1000 ? null : page.nextToken();
            } while (token != null && !token.isEmpty());
            if (processed > 0) {
                log.info("[doc-log-cleanup] {}: candidates={}, deleted={}（保留期={}天）",
                        dryRun ? "dry-run 候选统计（未删除）" : "清理完成",
                        processed, deleted, TimeUnit.MILLISECONDS.toDays(RETENTION_MS));
            }
        } finally {
            MDC.remove("traceId");
            running.set(false);
        }
    }
}
