package com.example.runtime.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessagePublisher;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.controller.TransitionController;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.infrastructure.event.TablestoreEventOutboxStore;
import com.example.runtime.infrastructure.saga.TablestoreSagaStore;
import com.example.runtime.service.IdempotencyService;
import com.example.runtime.service.event.OutboxRelayService;
import com.example.runtime.service.saga.SagaOrchestrator;
import com.example.runtime.service.saga.SagaOrchestrator.SagaCompensatedException;
import com.example.runtime.service.saga.SagaOrchestrator.SagaResult;
import com.example.runtime.service.saga.SagaProperties;
import com.example.runtime.service.saga.SagaStepHttpClient;
import com.example.runtime.service.saga.SagaStepHttpClient.StepResponse;
import com.example.runtime.service.transition.TransitionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * runtime-service 可观测性指标绑定测试（任务 #20，design/11 §4.2）：
 * <ul>
 *   <li>Outbox 中继：投递成功计数 + 待投递 Gauge（扫描可见行）；</li>
 *   <li>Saga：执行完成/冲正计数（bindMetrics 后打点，未绑定不抛异常）；</li>
 *   <li>流转入口：成功/失败计数。</li>
 * </ul>
 */
class RuntimeMetricsBindingTest {

    private InMemoryTablestore template;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
    }

    // ==================== Outbox 中继指标 ====================

    /** 直插一条待投递 outbox 行（与 OutboxRelayTest 同构） */
    private String insertPendingRow(String partitionKey, long nextRetryTime) {
        String eventId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("event_type", TsColumnValue.of(DocMqConstants.EventTypes.DOC_STATUS_CHANGED));
        columns.put("event_body", TsColumnValue.of("{\"event_id\":\"" + eventId
                + "\",\"event_type\":\"DOC_STATUS_CHANGED\",\"schema_version\":\"1.1\","
                + "\"timestamp\":" + now + ",\"payload\":{},"
                + "\"user_properties\":{\"traceparent\":\"trace-m\"}}"));
        columns.put("target_code", TsColumnValue.of("po"));
        columns.put("target_uuid", TsColumnValue.of("uuid-m"));
        columns.put("send_status", TsColumnValue.of(0L));
        columns.put("retry_count", TsColumnValue.of(0L));
        columns.put("next_retry_time", TsColumnValue.of(nextRetryTime));
        columns.put("event_seq", TsColumnValue.of(1L));
        columns.put("previous_event_seq", TsColumnValue.of(0L));
        columns.put("create_time", TsColumnValue.of(now));
        template.putRow("event_outbox", new TsRow(
                TsPrimaryKey.builder()
                        .add("partition_key", partitionKey)
                        .add("event_id", eventId).build(), columns));
        return eventId;
    }

    @Test
    @DisplayName("Outbox：bindMetrics 后扫描投递 → relay delivered+1、pending Gauge=扫描可见行数")
    void outboxRelay_metrics_deliveredAndGauge() {
        TablestoreEventOutboxStore store = new TablestoreEventOutboxStore(template);
        DocMessagePublisher publisher = new DocMessagePublisher() {
            @Override
            public boolean publish(DocMessage message) {
                return true;
            }

            @Override
            public String mode() {
                return "mock";
            }
        };
        OutboxRelayService relay = new OutboxRelayService(store,
                new com.example.runtime.infrastructure.event.TablestoreDeliveryCursorStore(template),
                publisher, Runnable::run, new ObjectMapper());
        MeterRegistry registry = new SimpleMeterRegistry();
        relay.bindMetrics(registry);

        insertPendingRow(PartitionKeys.dataKey("po", "uuid-m"), System.currentTimeMillis());
        relay.scanAndDeliver();

        assertThat(registry.get("ddp_outbox_relay_total")
                .tag("result", "delivered").counter().count()).isEqualTo(1.0);
        // 扫描时该行 send_status=0 可见 → Gauge=1（投递后不回减，下一轮扫描归零）
        assertThat(registry.get("ddp_outbox_pending").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Outbox：未绑定 metrics 时投递正常（null-safe，手工构造场景）")
    void outboxRelay_unbound_noError() {
        TablestoreEventOutboxStore store = new TablestoreEventOutboxStore(template);
        DocMessagePublisher publisher = new DocMessagePublisher() {
            @Override
            public boolean publish(DocMessage message) {
                return true;
            }

            @Override
            public String mode() {
                return "mock";
            }
        };
        OutboxRelayService relay = new OutboxRelayService(store,
                new com.example.runtime.infrastructure.event.TablestoreDeliveryCursorStore(template),
                publisher, Runnable::run, new ObjectMapper());
        String partitionKey = PartitionKeys.dataKey("po", "uuid-m2");
        String eventId = insertPendingRow(partitionKey, System.currentTimeMillis());

        relay.enqueue(partitionKey, eventId);

        assertThat(store.find(partitionKey, eventId).orElseThrow().getSendStatus())
                .isEqualTo(1L);
    }

    // ==================== Saga 指标 ====================

    private SagaOrchestrator newOrchestrator(MeterRegistry registry, String... failedUrls) {
        TablestoreSagaStore sagaStore = new TablestoreSagaStore(template);
        SagaStepHttpClient stepClient = mock(SagaStepHttpClient.class);
        List<String> failed = List.of(failedUrls);
        when(stepClient.post(anyString(), anyString(), any(TxPlan.TxStep.class), anyString()))
                .thenAnswer(invocation -> failed.contains((String) invocation.getArgument(0))
                        ? new StepResponse(500, "{\"error\":\"injected\"}", true)
                        : new StepResponse(200, "{\"ok\":true}", false));
        SagaOrchestrator orchestrator = new SagaOrchestrator(sagaStore, stepClient,
                new SagaProperties(), new ObjectMapper());
        orchestrator.bindMetrics(registry);
        return orchestrator;
    }

    private TxPlan plan() {
        return new TxPlan("po", 200L, 20L, List.of(
                new TxPlan.TxStep(1, "SAP", "http://mock/sap/action",
                        "http://mock/sap/compensate", 5000L, 0),
                new TxPlan.TxStep(2, "APS", "http://mock/aps/action",
                        "http://mock/aps/compensate", 5000L, 0)));
    }

    @Test
    @DisplayName("Saga：正向完成 → ddp_saga_execution_total{completed}+1")
    void saga_completedCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SagaOrchestrator orchestrator = newOrchestrator(registry);

        SagaResult result = orchestrator.execute(plan(), "po", "uuid-s1", 1L, 200L, 20L,
                "alice", "key-s1");
        // 外部步骤成功只到 ACTIONS_SUCCEEDED；本地提交后 markCommitted 才计 completed
        orchestrator.markCommitted(result.sagaId());

        assertThat(registry.get("ddp_saga_execution_total")
                .tag("result", "completed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Saga：步骤失败冲正 → compensated+1，死信计数不动")
    void saga_compensatedCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SagaOrchestrator orchestrator = newOrchestrator(registry, "http://mock/aps/action");

        assertThatThrownBy(() -> orchestrator.execute(plan(), "po", "uuid-s2",
                1L, 200L, 20L, "alice", "key-s2"))
                .isInstanceOf(SagaCompensatedException.class);

        assertThat(registry.get("ddp_saga_execution_total")
                .tag("result", "compensated").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ddp_saga_dead_letter_total").counter().count())
                .isZero();
    }

    // ==================== 流转入口指标 ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("流转：成功 success+1；服务异常 failed+1 且异常上抛")
    void transition_successAndFailedCounters() {
        TransitionService transitionService = mock(TransitionService.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.execute(any(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(2)).get());
        TransitionController controller = new TransitionController(transitionService,
                idempotencyService);
        MeterRegistry registry = new SimpleMeterRegistry();
        controller.bindMetrics(registry);

        when(transitionService.transition(anyString(), anyString(), anyLong(),
                anyString(), anyString(), any())).thenReturn(Map.of("uuid", "u1"));
        controller.transition("po", "u1", Map.of("target_status", 10, "event", "submit"),
                null, "alice");
        assertThat(registry.get("ddp_transition_total")
                .tag("result", "success").counter().count()).isEqualTo(1.0);

        when(transitionService.transition(anyString(), anyString(), anyLong(),
                anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("rule rejected"));
        assertThatThrownBy(() -> controller.transition("po", "u1",
                Map.of("target_status", 10, "event", "submit"), null, "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("ddp_transition_total")
                .tag("result", "failed").counter().count()).isEqualTo(1.0);
    }
}
