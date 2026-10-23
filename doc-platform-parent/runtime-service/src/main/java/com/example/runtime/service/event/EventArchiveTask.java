package com.example.runtime.service.event;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.runtime.domain.event.EventOutboxStore;
import com.example.runtime.domain.event.OutboxEvent;

/**
 * event_outbox 已发送行清理任务（定时开关默认关；默认 dry-run）。
 *
 * <p>扫描 send_status=1 AND create_time ≤ now-7d（event_outbox_index 续翻）。
 * 注意：这是在线表删除，不是持久归档；需要长期留存的场景必须先落地外部归档流程。
 * event_outbox/audit_log 表定义已移除 TTL（OTS「索引 + TTL 互斥」约束），因此
 * 不能依赖表级 TTL 兜底。开关 {@code doc.runtime.outbox.archive-enabled} 缺省 false；
 * {@code doc.runtime.outbox.archive-dry-run} 缺省 true，仅统计候选不删除。</p>
 */
@Component
public class EventArchiveTask {

    /** 归档保留期（7 天） */
    static final long RETENTION_MS = 7L * 24 * 3600 * 1000;

    private static final Logger log = LoggerFactory.getLogger(EventArchiveTask.class);

    private final EventOutboxStore outboxStore;

    @Value("${doc.runtime.outbox.archive-enabled:false}")
    private boolean archiveEnabled;

    /** dry-run：仅统计候选，不删除（正式清理需显式关闭） */
    @Value("${doc.runtime.outbox.archive-dry-run:true}")
    private boolean dryRun;

    /** 重叠执行防护 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EventArchiveTask(EventOutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    /** 每小时一轮清理/统计（开关默认关；单轮至多 1000 行；默认 dry-run） */
    @Scheduled(fixedDelayString = "${doc.runtime.outbox.archive-interval-ms:3600000}",
            initialDelayString = "${doc.runtime.outbox.archive-interval-ms:3600000}")
    public void archive() {
        if (!archiveEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        MDC.put(SagaMdcKeys.TRACE_ID, "outbox-archive-"
                + java.util.UUID.randomUUID().toString().substring(0, 8));
        try {
            long before = System.currentTimeMillis() - RETENTION_MS;
            int processed = 0;
            String token = null;
            do {
                EventOutboxStore.ScanPage page = outboxStore.scanSent(before, 100, token);
                for (OutboxEvent event : page.events()) {
                    if (!dryRun) {
                        try {
                            outboxStore.delete(event);
                        } catch (RuntimeException e) {
                            log.warn("[outbox-archive] 删除失败（下轮重试）: eventId={}", event.getEventId());
                        }
                    }
                    processed++;
                    if (processed >= 1000) {
                        break;
                    }
                }
                token = processed >= 1000 ? null : page.nextToken();
            } while (token != null && !token.isEmpty());
            if (processed > 0) {
                log.info("[outbox-archive] {}: candidates={}（保留期={}天）",
                        dryRun ? "dry-run 候选统计（未删除）" : "已发送行清理完成",
                        processed, TimeUnit.MILLISECONDS.toDays(RETENTION_MS));
            }
        } finally {
            MDC.remove(SagaMdcKeys.TRACE_ID);
            running.set(false);
        }
    }

    /** MDC 键收口（避免对 saga 包的编译依赖环） */
    static final class SagaMdcKeys {
        static final String TRACE_ID = "traceId";

        private SagaMdcKeys() {
        }
    }
}
