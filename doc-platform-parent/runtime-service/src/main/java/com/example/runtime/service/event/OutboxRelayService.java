package com.example.runtime.service.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.example.runtime.domain.event.EventOutboxStore;
import com.example.runtime.domain.event.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Transactional Outbox 中继（EV-01/02，design/07 §四）：
 * <ol>
 *   <li>即时首发：事务提交后经 {@link #enqueue} 入内存队列，worker 线程
 *       （outbox-relay 池）异步投递；</li>
 *   <li>扫描兜底：5s 定时经 event_outbox_index（send_status=0 AND
 *       next_retry_time≤now，limit100 + next_token 续翻）兜底投递；</li>
 *   <li>CAS 抢占：next_retry_time 条件更新防多实例重发（抢占窗口 10min）；</li>
 *   <li>退避：5s/15s/45s/2min/5min/10min/30min/60min 八级，retry_count≥8
 *       转 send_status=2 待人工（手动重发接口 P-OP-03）。</li>
 * </ol>
 *
 * <p>消息体按 design/07 v1.1：body 即 event_body 列（含 event_id/event_type/
 * schema_version/timestamp/payload）；event_id/event_type/traceparent 写
 * userProperties。投递经 MQ 门面（doc.mq.mode mock/rocketmq 双模式防腐）。</p>
 */
@Service
public class OutboxRelayService {

    /** 8 级退避序列毫秒（design/07 §四） */
    static final long[] BACKOFF_MS = {
            5_000L, 15_000L, 45_000L, 120_000L, 300_000L, 600_000L, 1_800_000L, 3_600_000L};

    /** CAS 抢占窗口（now+10min，防多实例重发） */
    static final long CLAIM_WINDOW_MS = 600_000L;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final EventOutboxStore outboxStore;
    private final DocMessagePublisher messagePublisher;
    private final Executor relayExecutor;
    private final ObjectMapper objectMapper;

    /** 即时首发内存队列（进程内尽力而为，丢失由 5s 扫描兜底） */
    private final LinkedBlockingQueue<OutboxRef> immediateQueue = new LinkedBlockingQueue<>(1000);

    /** 最近一轮扫描可见的待投递行数（send_status=0 且重试窗口已至；Gauge 源，design/11 §4.2 ddp_outbox_pending） */
    private final AtomicLong pendingGauge = new AtomicLong();

    /** 中继投递计数（bindMetrics 后生效；测试手工构造为 null 不打点） */
    private Counter deliveredCounter;
    private Counter failedCounter;
    private Counter manualCounter;

    public OutboxRelayService(EventOutboxStore outboxStore,
                              DocMessagePublisher messagePublisher,
                              @Qualifier("outboxRelayExecutor") Executor relayExecutor,
                              ObjectMapper objectMapper) {
        this.outboxStore = outboxStore;
        this.messagePublisher = messagePublisher;
        this.relayExecutor = relayExecutor;
        this.objectMapper = objectMapper;
    }

    /** 即时投递引用（partition_key + event_id，worker 侧强一致重读） */
    public record OutboxRef(String partitionKey, String eventId) {
    }

    // ==================== 即时首发 ====================

    /**
     * 事务提交后即时首发入队（流转/建单服务在事务提交后调用）。
     *
     * @param partitionKey 分区键
     * @param eventId      事件 ID
     */
    public void enqueue(String partitionKey, String eventId) {
        OutboxRef ref = new OutboxRef(partitionKey, eventId);
        if (!immediateQueue.offer(ref)) {
            log.warn("[outbox] 即时队列已满，交由扫描兜底: eventId={}", eventId);
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
            // 拒绝策略 CallerRuns 下理论不可达；防御性兜底（扫描会捡起）
            log.warn("[outbox] 即时投递任务提交失败，扫描兜底: eventId={}", eventId);
        }
    }

    /** 排空队列逐个投递（worker 主体） */
    private void drainOnce() {
        List<OutboxRef> batch = new ArrayList<>();
        immediateQueue.drainTo(batch, 50);
        for (OutboxRef ref : batch) {
            try {
                deliverImmediate(ref);
            } catch (RuntimeException e) {
                log.warn("[outbox] 即时投递异常，扫描兜底: eventId={}, cause={}",
                        ref.eventId(), e.getMessage());
            }
        }
    }

    /** 即时投递：主键强一致重读 → CAS 抢占 → 发送（抢占失败/已发送直接跳过） */
    private void deliverImmediate(OutboxRef ref) {
        OutboxEvent event = outboxStore.find(ref.partitionKey(), ref.eventId()).orElse(null);
        if (event == null || event.getSendStatus() != 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.getNextRetryTime() > now) {
            // 已被抢占或退避窗口未至
            return;
        }
        if (!outboxStore.casClaim(event, event.getNextRetryTime(), now + CLAIM_WINDOW_MS)) {
            return;
        }
        event.setNextRetryTime(now + CLAIM_WINDOW_MS);
        deliver(event);
    }

    // ==================== 5s 扫描兜底 ====================

    /**
     * 定时扫描兜底（每 5s）：event_outbox_index 检索 send_status=0 AND
     * next_retry_time≤now，limit100 + next_token 续翻至尽；逐行 CAS 抢占后投递。
     */
    @Scheduled(fixedDelayString = "${doc.runtime.outbox.scan-interval-ms:5000}",
            initialDelayString = "${doc.runtime.outbox.scan-initial-delay-ms:10000}")
    public void scanAndDeliver() {
        String token = null;
        int total = 0;
        int seenPending = 0;
        do {
            long now = System.currentTimeMillis();
            EventOutboxStore.ScanPage page = outboxStore.scanPending(now, 100, token);
            seenPending += page.events().size();
            for (OutboxEvent event : page.events()) {
                try {
                    if (outboxStore.casClaim(event, event.getNextRetryTime(),
                            now + CLAIM_WINDOW_MS)) {
                        event.setNextRetryTime(now + CLAIM_WINDOW_MS);
                        deliver(event);
                        total++;
                    }
                } catch (RuntimeException e) {
                    log.warn("[outbox] 扫描投递异常: eventId={}, cause={}",
                            event.getEventId(), e.getMessage());
                }
            }
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        pendingGauge.set(seenPending);
        if (total > 0) {
            log.info("[outbox] 扫描兜底投递完成: count={}", total);
        }
    }

    // ==================== 投递与退避 ====================

    /**
     * 单事件投递：成功 markSent；失败按 8 级退避 markRetry，
     * retry_count≥8 转 send_status=2 待人工。
     */
    void deliver(OutboxEvent event) {
        try {
            DocMessage message = toMessage(event);
            boolean sent = messagePublisher.publish(message);
            if (sent) {
                outboxStore.markSent(event);
                increment(deliveredCounter);
                log.info("[outbox] 事件投递成功: eventId={}, type={}, mode={}",
                        event.getEventId(), event.getEventType(), messagePublisher.mode());
            } else {
                failWithBackoff(event, "publisher 返回 false（broker 不可用）");
            }
        } catch (RuntimeException e) {
            failWithBackoff(event, e.getMessage());
        }
    }

    /** 失败退避推进（retry_count 基于行当前值 +1） */
    private void failWithBackoff(OutboxEvent event, String reason) {
        long retryCount = event.getRetryCount();
        boolean toManual = retryCount + 1 >= BACKOFF_MS.length;
        long backoff = BACKOFF_MS[(int) Math.min(retryCount, BACKOFF_MS.length - 1)];
        long nextRetry = System.currentTimeMillis() + backoff;
        outboxStore.markRetry(event, nextRetry, reason, toManual);
        increment(toManual ? manualCounter : failedCounter);
        if (toManual) {
            log.error("[outbox][ALERT] 事件投递退避耗尽转待人工: eventId={}, type={}, lastError={}",
                    event.getEventId(), event.getEventType(), reason);
        } else {
            log.warn("[outbox] 事件投递失败退避: eventId={}, retry={}, nextInMs={}, cause={}",
                    event.getEventId(), retryCount + 1, backoff, reason);
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
            // DOC_STATUS_CHANGED / DOC_CREATED：FIFO（Sharding Key = doc_uuid）
            topic = DocMqConstants.Topics.DOC_STATUS_EVENTS;
            mode = MessageMode.ORDERLY;
            shardingKey = event.getTargetUuid();
        }
        String body = event.getEventBody() == null ? "{}" : event.getEventBody();
        DocMessage message = new DocMessage(topic, event.getTargetCode(), shardingKey,
                body, mode)
                .withUserProperty("event_id", event.getEventId())
                .withUserProperty("event_type", event.getEventType());
        String traceparent = extractTraceparent(body);
        if (traceparent != null) {
            message.withUserProperty("traceparent", traceparent);
        }
        return message;
    }

    /** 从事件体提取 traceparent（v1.1 信封 user_properties.traceparent，无则 null） */
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

    /** 即时队列当前深度（监控/测试用） */
    public int immediateQueueDepth() {
        return immediateQueue.size();
    }

    // ==================== 可观测性（design/11 §4.2 / 任务 #20） ====================

    /**
     * 绑定 Outbox 指标：{@code ddp_outbox_pending}（Gauge，最近扫描可见待投递行数
     * + 即时队列深度）、{@code ddp_outbox_relay_total{result=delivered|failed|manual}}。
     * Setter 注入（required=false）：无 actuator/测试手工构造场景不打点。
     *
     * @param registry Micrometer 注册表
     */
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

    /** 计数器空安全自增（未绑定 metrics 时 NOOP） */
    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
