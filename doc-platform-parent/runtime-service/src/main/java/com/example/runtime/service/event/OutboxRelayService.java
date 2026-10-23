package com.example.runtime.service.event;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessagePublisher;
import com.example.common.infrastructure.messaging.MessageMode;
import com.example.runtime.domain.event.DeliveryCursorStore;
import com.example.runtime.domain.event.DocDeliveryCursor;
import com.example.runtime.domain.event.EventOutboxStore;
import com.example.runtime.domain.event.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Transactional Outbox 中继（EV-01/02）。
 *
 * <p><b>按单据顺序</b>：每个单据维护 {@code event_delivery_cursor}（last_sent_seq +
 * 租约）。同一时刻只有持有租约的实例推进最早未确认 eventSeq；事件失败退避时
 * 阻止后继越序；退避耗尽转待人工并阻断该单据序列。发送成功但 ACK/游标推进前
 * 崩溃会重发同一 eventId，属于允许的 at-least-once。</p>
 *
 * <p>扫描仍用 event_outbox_index 发现候选，实际发送前用 event_outbox_seq_index
 * 按 event_seq 升序重读并确认无缺口；多元索引最终一致导致候选暂时不可见时
 * 只等待，不跳过。</p>
 */
@Service
public class OutboxRelayService {

    static final long[] BACKOFF_MS = {
            5_000L, 15_000L, 45_000L, 120_000L, 300_000L, 600_000L, 1_800_000L, 3_600_000L};

    static final long CLAIM_WINDOW_MS = 600_000L;

    static final long CURSOR_LEASE_MS = 60_000L;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final EventOutboxStore outboxStore;
    private final DeliveryCursorStore cursorStore;
    private final DocMessagePublisher messagePublisher;
    private final Executor relayExecutor;
    private final ObjectMapper objectMapper;

    private final LinkedBlockingQueue<OutboxRef> immediateQueue = new LinkedBlockingQueue<>(1000);
    private final AtomicLong pendingGauge = new AtomicLong();
    private final String instanceId = "relay-" + UUID.randomUUID().toString().substring(0, 8);

    private Counter deliveredCounter;
    private Counter failedCounter;
    private Counter manualCounter;

    public OutboxRelayService(EventOutboxStore outboxStore, DeliveryCursorStore cursorStore,
                              DocMessagePublisher messagePublisher,
                              @Qualifier("outboxRelayExecutor") Executor relayExecutor,
                              ObjectMapper objectMapper) {
        this.outboxStore = outboxStore;
        this.cursorStore = cursorStore;
        this.messagePublisher = messagePublisher;
        this.relayExecutor = relayExecutor;
        this.objectMapper = objectMapper;
    }

    /** 即时投递引用（partition_key + event_id 兼容旧调用）。 */
    public record OutboxRef(String partitionKey, String eventId) {
    }

    // ==================== 即时首发 ====================

    /**
     * 兼容旧调用：通过 eventId 定位事件后按单据顺序投递。
     */
    public void enqueue(String partitionKey, String eventId) {
        OutboxEvent event = outboxStore.find(partitionKey, eventId).orElse(null);
        if (event == null || event.getEventSeq() <= 0) {
            log.debug("[outbox] 即时事件尚未投影或为旧行，交由扫描兜底: eventId={}", eventId);
            return;
        }
        enqueueDoc(partitionKey, event.getTargetUuid());
    }

    /** 投影完成后按单据即时唤醒中继。 */
    public void enqueueDoc(String partitionKey, String docUuid) {
        if (docUuid == null || docUuid.isBlank()) {
            return;
        }
        OutboxRef ref = new OutboxRef(partitionKey, docUuid);
        if (!immediateQueue.offer(ref)) {
            log.warn("[outbox] 即时队列已满，交由扫描兜底: docUuid={}", docUuid);
            return;
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        try {
            relayExecutor.execute(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    drainOnce();
                } finally {
                    MDC.clear();
                }
            });
        } catch (RuntimeException e) {
            log.warn("[outbox] 即时投递任务提交失败，扫描兜底: docUuid={}", docUuid);
        }
    }

    private void drainOnce() {
        List<OutboxRef> batch = new ArrayList<>();
        immediateQueue.drainTo(batch, 50);
        for (OutboxRef ref : batch) {
            try {
                deliverDoc(ref.partitionKey(), ref.eventId());
            } catch (RuntimeException e) {
                log.warn("[outbox] 即时投递异常，扫描兜底: docUuid={}, cause={}",
                        ref.eventId(), e.getMessage());
            }
        }
    }

    // ==================== 扫描兜底 ====================

    @Scheduled(fixedDelayString = "${doc.runtime.outbox.scan-interval-ms:5000}",
            initialDelayString = "${doc.runtime.outbox.scan-initial-delay-ms:10000}")
    public void scanAndDeliver() {
        String token = null;
        int total = 0;
        int seenPending = 0;
        Set<String> docs = new LinkedHashSet<>();
        do {
            long now = System.currentTimeMillis();
            EventOutboxStore.ScanPage page = outboxStore.scanPending(now, 100, token);
            seenPending += page.events().size();
            for (OutboxEvent event : page.events()) {
                if (event.getTargetUuid() != null) {
                    docs.add(event.getPartitionKey() + "\u0000" + event.getTargetUuid());
                }
            }
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        for (String doc : docs) {
            int split = doc.indexOf('\u0000');
            try {
                if (deliverDoc(doc.substring(0, split), doc.substring(split + 1))) {
                    total++;
                }
            } catch (RuntimeException e) {
                log.warn("[outbox] 扫描投递异常: doc={}, cause={}",
                        doc.substring(split + 1), e.getMessage());
            }
        }
        pendingGauge.set(seenPending);
        if (total > 0) {
            log.info("[outbox] 扫描兜底投递完成: docs={}", total);
        }
    }

    // ==================== 按单据顺序投递 ====================

    /**
     * 投递一个单据的连续事件序列。
     *
     * @return true=至少推进了一条事件
     */
    boolean deliverDoc(String partitionKey, String docUuid) {
        DocDeliveryCursor cursor = cursorStore.getOrCreate(partitionKey, docUuid);
        long now = System.currentTimeMillis();
        if (cursor.blocked()) {
            log.error("[outbox][ALERT] 单据事件序列已被死信阻断，需人工处理: docUuid={}, seq={}, reason={}",
                    docUuid, cursor.getBlockedEventSeq(), cursor.getBlockedReason());
            return false;
        }
        if (cursor.getLeaseUntil() > now && cursor.getLeaseOwner() != null
                && !cursor.getLeaseOwner().isBlank()) {
            return false;
        }
        if (!cursorStore.claim(cursor, instanceId, now + CURSOR_LEASE_MS)) {
            return false;
        }
        boolean advanced = false;
        try {
            while (true) {
                List<OutboxEvent> events = outboxStore.scanByDocAfterSeq(
                        docUuid, cursor.getLastSentSeq(), 100);
                if (events.isEmpty()) {
                    return advanced;
                }
                boolean progressed = false;
                for (OutboxEvent event : events) {
                    long seq = event.getEventSeq();
                    if (seq <= cursor.getLastSentSeq()) {
                        continue;
                    }
                    if (event.getSendStatus() == 2L) {
                        cursorStore.block(cursor, seq,
                                "事件已转待人工: " + event.getEventId(), instanceId);
                        log.error("[outbox][ALERT] 发现待人工事件，阻断单据序列: docUuid={}, seq={}",
                                docUuid, seq);
                        return advanced;
                    }
                    if (event.getSendStatus() == 1L) {
                        if (!cursorStore.advance(cursor, seq, instanceId)) {
                            return advanced;
                        }
                        advanced = true;
                        progressed = true;
                        continue;
                    }
                    long expected = cursor.getLastSentSeq() + 1;
                    if (seq > expected) {
                        // 更早事件尚未被索引可见：等待，绝不越序
                        log.warn("[outbox] 事件序列存在缺口，等待前驱可见: docUuid={}, expected={}, actual={}",
                                docUuid, expected, seq);
                        return advanced;
                    }
                    if (event.getPreviousEventSeq() > 0
                            && event.getPreviousEventSeq() != cursor.getLastSentSeq()) {
                        log.warn("[outbox] previous_event_seq 与 checkpoint 不一致，等待人工核对: "
                                        + "docUuid={}, seq={}, previous={}, checkpoint={}",
                                docUuid, seq, event.getPreviousEventSeq(),
                                cursor.getLastSentSeq());
                        return advanced;
                    }
                    long current = System.currentTimeMillis();
                    if (event.getNextRetryTime() > current) {
                        return advanced;
                    }
                    if (!outboxStore.casClaim(event, event.getNextRetryTime(),
                            current + CLAIM_WINDOW_MS)) {
                        return advanced;
                    }
                    event.setNextRetryTime(current + CLAIM_WINDOW_MS);
                    if (!deliver(event, cursor)) {
                        return advanced;
                    }
                    if (!cursorStore.advance(cursor, seq, instanceId)) {
                        log.warn("[outbox] checkpoint 推进竞争失败（下一轮从已发送行补齐）: docUuid={}, seq={}",
                                docUuid, seq);
                        return advanced;
                    }
                    advanced = true;
                    progressed = true;
                }
                if (!progressed) {
                    return advanced;
                }
            }
        } finally {
            if (instanceId.equals(cursor.getLeaseOwner())) {
                cursorStore.release(cursor, instanceId);
            }
        }
    }

    /**
     * 单事件投递：成功 markSent；失败按 8 级退避 markRetry，
     * retry_count≥8 转 send_status=2 并阻断该单据序列。
     */
    private boolean deliver(OutboxEvent event, DocDeliveryCursor cursor) {
        try {
            DocMessage message = toMessage(event);
            boolean sent = messagePublisher.publish(message);
            if (sent) {
                outboxStore.markSent(event);
                increment(deliveredCounter);
                log.info("[outbox] 事件投递成功: eventId={}, seq={}, type={}, mode={}",
                        event.getEventId(), event.getEventSeq(), event.getEventType(),
                        messagePublisher.mode());
                return true;
            }
            failWithBackoff(event, "publisher 返回 false（broker 不可用）", cursor);
            return false;
        } catch (RuntimeException e) {
            failWithBackoff(event, e.getMessage(), cursor);
            return false;
        }
    }

    private void failWithBackoff(OutboxEvent event, String reason, DocDeliveryCursor cursor) {
        long retryCount = event.getRetryCount();
        boolean toManual = retryCount + 1 >= BACKOFF_MS.length;
        long backoff = BACKOFF_MS[(int) Math.min(retryCount, BACKOFF_MS.length - 1)];
        long nextRetry = System.currentTimeMillis() + backoff;
        outboxStore.markRetry(event, nextRetry, reason, toManual);
        increment(toManual ? manualCounter : failedCounter);
        if (toManual) {
            cursorStore.block(cursor, event.getEventSeq(),
                    "事件投递退避耗尽: " + event.getEventId() + ", " + reason, instanceId);
            log.error("[outbox][ALERT] 事件投递退避耗尽转待人工并阻断单据序列: "
                            + "eventId={}, docUuid={}, seq={}, lastError={}",
                    event.getEventId(), event.getTargetUuid(), event.getEventSeq(), reason);
        } else {
            log.warn("[outbox] 事件投递失败退避: eventId={}, seq={}, retry={}, nextInMs={}, cause={}",
                    event.getEventId(), event.getEventSeq(), retryCount + 1, backoff, reason);
        }
    }

    /** outbox 行 → MQ 门面消息（Topic/Tag/ShardingKey/userProperties 按 design/07 §2） */
    DocMessage toMessage(OutboxEvent event) {
        String topic;
        MessageMode mode;
        String shardingKey = null;
        if (DocMqConstants.EventTypes.ENTITY_CHANGED.equals(event.getEventType())) {
            topic = DocMqConstants.Topics.ENTITY_CHANGE_EVENTS;
            mode = MessageMode.NORMAL;
        } else {
            topic = DocMqConstants.Topics.DOC_STATUS_EVENTS;
            mode = MessageMode.ORDERLY;
            shardingKey = event.getTargetUuid();
        }
        String body = event.getEventBody() == null ? "{}" : event.getEventBody();
        DocMessage message = new DocMessage(topic, event.getTargetCode(), shardingKey,
                body, mode)
                .withUserProperty("event_id", event.getEventId())
                .withUserProperty("event_type", event.getEventType());
        if (event.getEventSeq() > 0) {
            message.withUserProperty("event_seq", String.valueOf(event.getEventSeq()));
        }
        String traceparent = extractTraceparent(body);
        if (traceparent != null) {
            message.withUserProperty("traceparent", traceparent);
        }
        return message;
    }

    private String extractTraceparent(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode traceparent = node.path("user_properties").path("traceparent");
            if (traceparent.isTextual() && !traceparent.asText().isBlank()) {
                return traceparent.asText();
            }
            JsonNode payloadTrace = node.path("payload").path("traceparent");
            if (payloadTrace.isTextual() && !payloadTrace.asText().isBlank()) {
                return payloadTrace.asText();
            }
        } catch (JsonProcessingException e) {
            log.debug("[outbox] traceparent 提取失败（忽略）: {}", e.getMessage());
        }
        return null;
    }

    public int immediateQueueDepth() {
        return immediateQueue.size();
    }

    // ==================== 可观测性 ====================

    @Autowired(required = false)
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder("ddp_outbox_pending", this,
                relay -> relay.pendingGauge.get() + relay.immediateQueue.size())
                .description("outbox 待投递行数（最近扫描 send_status=0 可见行 + 即时队列深度）")
                .register(registry);
        this.deliveredCounter = Counter.builder("ddp_outbox_relay_total")
                .tag("result", "delivered").register(registry);
        this.failedCounter = Counter.builder("ddp_outbox_relay_total")
                .tag("result", "failed").register(registry);
        this.manualCounter = Counter.builder("ddp_outbox_relay_total")
                .tag("result", "manual").register(registry);
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
