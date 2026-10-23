package com.example.runtime.service.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessagePublisher;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.event.OutboxEvent;
import com.example.runtime.infrastructure.event.TablestoreEventOutboxStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Transactional Outbox 中继测试（EV-01/02，InMemoryTablestore + 内存 publisher）：
 * <ul>
 *   <li>即时首发：enqueue 后经 worker 立即投递 → send_status=1；</li>
 *   <li>失败退避：publisher false → retry_count+1 / next_retry_time=+5s；</li>
 *   <li>退避耗尽：retry_count≥8 → send_status=2 待人工；</li>
 *   <li>5s 扫描兜底：到期行投递、未到期行跳过；</li>
 *   <li>CAS 抢占：next_retry_time 期望值不符即失败（防多实例重发）；</li>
 *   <li>event_id 去重（Redis SETNX 降级进程内）：首次 true / 重放 false。</li>
 * </ul>
 */
class OutboxRelayTest {

    private static final String DOC = "po";
    private static final String UUID = "uuid-outbox-1";

    private InMemoryTablestore template;
    private TablestoreEventOutboxStore store;
    private CapturingPublisher publisher;
    private OutboxRelayService relay;
    private String partitionKey;
    private long nextSeq = 1L;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        store = new TablestoreEventOutboxStore(template);
        publisher = new CapturingPublisher();
        // 同步执行器：测试内即时观察投递结果
        relay = new OutboxRelayService(store,
                new com.example.runtime.infrastructure.event.TablestoreDeliveryCursorStore(template),
                publisher, Runnable::run, new ObjectMapper());
        partitionKey = PartitionKeys.dataKey(DOC, UUID);
    }

    /** 直插一条 outbox 行（模拟投影写入侧，event_seq 单调递增） */
    private String insertPendingRow(long nextRetryTime, long retryCount) {
        String eventId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long seq = nextSeq++;
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("event_type", TsColumnValue.of(DocMqConstants.EventTypes.DOC_STATUS_CHANGED));
        columns.put("event_body", TsColumnValue.of("{\"event_id\":\"" + eventId
                + "\",\"event_type\":\"DOC_STATUS_CHANGED\",\"schema_version\":\"1.1\","
                + "\"timestamp\":" + now + ",\"payload\":{\"doc_uuid\":\"" + UUID + "\"},"
                + "\"user_properties\":{\"traceparent\":\"trace-abc\"}}"));
        columns.put("target_code", TsColumnValue.of(DOC));
        columns.put("target_uuid", TsColumnValue.of(UUID));
        columns.put("send_status", TsColumnValue.of(0L));
        columns.put("retry_count", TsColumnValue.of(retryCount));
        columns.put("next_retry_time", TsColumnValue.of(nextRetryTime));
        columns.put("event_seq", TsColumnValue.of(seq));
        columns.put("previous_event_seq", TsColumnValue.of(seq - 1));
        columns.put("create_time", TsColumnValue.of(now));
        template.putRow("event_outbox", new TsRow(
                TsPrimaryKey.builder()
                        .add("partition_key", partitionKey)
                        .add("event_id", eventId)
                        .build(), columns));
        return eventId;
    }

    @Test
    @DisplayName("即时首发：enqueue 后 worker 立即投递 → send_status=1 + userProperties 齐备（EV-01）")
    void immediateDelivery_marksSent() {
        String eventId = insertPendingRow(System.currentTimeMillis(), 0L);

        relay.enqueue(partitionKey, eventId);

        OutboxEvent after = store.find(partitionKey, eventId).orElseThrow();
        assertThat(after.getSendStatus()).isEqualTo(1L);
        assertThat(publisher.published).hasSize(1);
        DocMessage message = publisher.published.get(0);
        assertThat(message.getTopic()).isEqualTo(DocMqConstants.Topics.DOC_STATUS_EVENTS);
        assertThat(message.getShardingKey()).isEqualTo(UUID);
        assertThat(message.getUserProperties().get("event_id")).isEqualTo(eventId);
        assertThat(message.getUserProperties().get("traceparent")).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("投递失败 8 级退避：retry_count+1、next_retry_time=+5s、send_status 保持 0")
    void publishFailed_backoffFirstLevel() {
        publisher.succeed = false;
        long before = System.currentTimeMillis();
        String eventId = insertPendingRow(before, 0L);

        relay.enqueue(partitionKey, eventId);

        OutboxEvent after = store.find(partitionKey, eventId).orElseThrow();
        assertThat(after.getSendStatus()).isEqualTo(0L);
        assertThat(after.getRetryCount()).isEqualTo(1L);
        assertThat(after.getLastError()).isNotBlank();
        assertThat(after.getNextRetryTime())
                .isBetween(before + OutboxRelayService.BACKOFF_MS[0],
                        System.currentTimeMillis() + OutboxRelayService.BACKOFF_MS[0]);
    }

    @Test
    @DisplayName("退避耗尽转待人工：retry_count=7 再失败 → send_status=2（P-OP-03 入口）")
    void backoffExhausted_toManual() {
        publisher.succeed = false;
        String eventId = insertPendingRow(System.currentTimeMillis(), 7L);

        relay.enqueue(partitionKey, eventId);

        OutboxEvent after = store.find(partitionKey, eventId).orElseThrow();
        assertThat(after.getSendStatus()).isEqualTo(2L);
        assertThat(after.getRetryCount()).isEqualTo(8L);
    }

    @Test
    @DisplayName("5s 扫描兜底：到期行投递、未来窗口行跳过（EV-01 兜底语义）")
    void scanDeliversDueRows_skipsFutureRows() {
        String dueEventId = insertPendingRow(System.currentTimeMillis() - 1000L, 0L);
        String futureEventId = insertPendingRow(System.currentTimeMillis() + 600_000L, 0L);

        relay.scanAndDeliver();

        assertThat(store.find(partitionKey, dueEventId).orElseThrow().getSendStatus()).isEqualTo(1L);
        assertThat(store.find(partitionKey, futureEventId).orElseThrow().getSendStatus()).isEqualTo(0L);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    @DisplayName("同单据顺序：首事件失败时后继不发送；恢复后按 1→2 投递")
    void orderedDelivery_failureBlocksLaterEvents() {
        publisher.succeed = false;
        String first = insertPendingRow(System.currentTimeMillis(), 0L);
        String second = insertPendingRow(System.currentTimeMillis(), 0L);

        relay.scanAndDeliver();

        assertThat(store.find(partitionKey, first).orElseThrow().getSendStatus()).isEqualTo(0L);
        assertThat(store.find(partitionKey, first).orElseThrow().getRetryCount()).isEqualTo(1L);
        assertThat(store.find(partitionKey, second).orElseThrow().getSendStatus()).isEqualTo(0L);
        var cursor = new com.example.runtime.infrastructure.event
                .TablestoreDeliveryCursorStore(template)
                .find(partitionKey, UUID).orElseThrow();
        assertThat(cursor.getLastSentSeq()).isZero();
        assertThat(cursor.getBlockedEventSeq()).isZero();

        // 复位首事件退避后重试，必须按 event_seq 顺序投递
        template.updateRow("event_outbox",
                TsPrimaryKey.builder().add("partition_key", partitionKey)
                        .add("event_id", first).build(),
                List.of(com.example.common.infrastructure.tablestore.model.ColumnUpdate.put(
                                "next_retry_time", TsColumnValue.of(System.currentTimeMillis())),
                        com.example.common.infrastructure.tablestore.model.ColumnUpdate.put(
                                "retry_count", TsColumnValue.of(0L))));
        publisher.succeed = true;
        relay.scanAndDeliver();

        assertThat(publisher.published).hasSize(2);
        assertThat(publisher.published).extracting(message ->
                        message.getUserProperties().get("event_seq"))
                .containsExactly("1", "2");
        assertThat(store.find(partitionKey, first).orElseThrow().getSendStatus()).isEqualTo(1L);
        assertThat(store.find(partitionKey, second).orElseThrow().getSendStatus()).isEqualTo(1L);
        var after = new com.example.runtime.infrastructure.event
                .TablestoreDeliveryCursorStore(template)
                .find(partitionKey, UUID).orElseThrow();
        assertThat(after.getLastSentSeq()).isEqualTo(2L);
    }

    @Test
    @DisplayName("退避耗尽阻断同单据序列：后继事件不越序，checkpoint 不推进")
    void manualEvent_blocksLaterEvents() {
        publisher.succeed = false;
        String first = insertPendingRow(System.currentTimeMillis(), 7L);
        String second = insertPendingRow(System.currentTimeMillis(), 0L);

        relay.scanAndDeliver();

        assertThat(store.find(partitionKey, first).orElseThrow().getSendStatus()).isEqualTo(2L);
        assertThat(store.find(partitionKey, second).orElseThrow().getSendStatus()).isEqualTo(0L);
        var cursor = new com.example.runtime.infrastructure.event
                .TablestoreDeliveryCursorStore(template)
                .find(partitionKey, UUID).orElseThrow();
        assertThat(cursor.getBlockedEventSeq()).isEqualTo(1L);
        assertThat(cursor.getLastSentSeq()).isZero();
    }

    @Test
    @DisplayName("CAS 抢占：期望 next_retry_time 不符即失败（防多实例重发）")
    void casClaim_onlyFirstWinner() {
        String eventId = insertPendingRow(System.currentTimeMillis(), 0L);
        OutboxEvent event = store.find(partitionKey, eventId).orElseThrow();

        boolean first = store.casClaim(event, event.getNextRetryTime(),
                System.currentTimeMillis() + OutboxRelayService.CLAIM_WINDOW_MS);
        // 第二实例仍持旧期望值 → 抢占失败
        boolean second = store.casClaim(event, event.getNextRetryTime(),
                System.currentTimeMillis() + OutboxRelayService.CLAIM_WINDOW_MS);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("event_id 去重（Redis 不可用降级进程内 LRU）：首次 true / 重放 false（EV-02）")
    void deduplication_firstThenSkip() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.opsForValue())
                .thenThrow(new RedisConnectionFailureException("redis down"));
        EventDeduplicator deduplicator = new EventDeduplicator(brokenRedis);

        assertThat(deduplicator.tryFirstConsume("evt-1")).isTrue();
        assertThat(deduplicator.tryFirstConsume("evt-1")).isFalse();
        assertThat(deduplicator.tryFirstConsume("evt-2")).isTrue();
    }

    @Test
    @DisplayName("消费落地与重复跳过：DocStatusEventConsumeService 证据记录（EV-02 骨架）")
    void consumeService_evidenceAndDedup() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.opsForValue())
                .thenThrow(new RedisConnectionFailureException("redis down"));
        DocStatusEventConsumeService consumeService = new DocStatusEventConsumeService(
                new EventDeduplicator(brokenRedis), new ObjectMapper());

        String body = "{\"event_id\":\"evt-c1\",\"event_type\":\"DOC_STATUS_CHANGED\"}";
        boolean first = consumeService.consume(body, Map.of("event_id", "evt-c1",
                "event_type", "DOC_STATUS_CHANGED"), "mock");
        boolean replay = consumeService.consume(body, Map.of("event_id", "evt-c1",
                "event_type", "DOC_STATUS_CHANGED"), "mock");

        assertThat(first).isTrue();
        assertThat(replay).isFalse();
        assertThat(consumeService.evidences(10)).hasSize(1);
        assertThat(consumeService.evidences(10).get(0).get("event_id")).isEqualTo("evt-c1");
    }

    /** 内存捕获 publisher（mock 门面同构：publish 可控成败） */
    private static final class CapturingPublisher implements DocMessagePublisher {

        private final List<DocMessage> published = new ArrayList<>();
        private volatile boolean succeed = true;

        @Override
        public boolean publish(DocMessage message) {
            if (!succeed) {
                return false;
            }
            published.add(message);
            return true;
        }

        @Override
        public String mode() {
            return "mock";
        }
    }
}
