package com.example.runtime.service.event;

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
 * event_outbox 已发送行归档清理任务骨架（任务清单 4：7 天归档，定时开关默认关）。
 *
 * <p>扫描 send_status=1 AND create_time ≤ now-7d（event_outbox_index 续翻），
 * 逐行删除；表级 TTL 30d 为最终兜底。开关 {@code doc.runtime.outbox.archive-enabled}
 * 缺省 false，生产经环境变量显式开启。</p>
 */
@Component
public class EventArchiveTask {

    /** 归档保留期（7 天） */
    static final long RETENTION_MS = 7L * 24 * 3600 * 1000;

    private static final Logger log = LoggerFactory.getLogger(EventArchiveTask.class);

    private final EventOutboxStore outboxStore;

    @Value("${doc.runtime.outbox.archive-enabled:false}")
    private boolean archiveEnabled;

    /** 重叠执行防护 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EventArchiveTask(EventOutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    /** 每小时一轮归档（开关默认关；单轮至多清理 1000 行） */
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
            int deleted = 0;
            String token = null;
            do {
                EventOutboxStore.ScanPage page = outboxStore.scanSent(before, 100, token);
                for (OutboxEvent event : page.events()) {
                    try {
                        outboxStore.delete(event);
                        deleted++;
                    } catch (RuntimeException e) {
                        log.warn("[outbox-archive] 删除失败（下轮重试）: eventId={}", event.getEventId());
                    }
                    if (deleted >= 1000) {
                        break;
                    }
                }
                token = deleted >= 1000 ? null : page.nextToken();
            } while (token != null && !token.isEmpty());
            if (deleted > 0) {
                log.info("[outbox-archive] 归档清理完成: deleted={}", deleted);
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
