package com.example.runtime.service.saga;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.infrastructure.saga.TablestoreSagaStore;
import com.example.runtime.service.saga.SagaOrchestrator.SagaCompensatedException;
import com.example.runtime.service.saga.SagaOrchestrator.SagaDeadException;
import com.example.runtime.service.saga.SagaOrchestrator.SagaResult;
import com.example.runtime.service.saga.SagaStepHttpClient.StepResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saga 编排器测试（TX-01/02/03，InMemoryTablestore + mock 步骤端点）：
 * <ul>
 *   <li>正向按 seq 顺序执行 → COMPLETED（design/05 §2.5）；</li>
 *   <li>步骤失败 → 逆序冲正已成功步 → COMPENSATED + SagaCompensatedException；</li>
 *   <li>冲正失败 → DEAD + saga_dead_letter 落表 + SagaDeadException；</li>
 *   <li>幂等：同幂等键重放命中 EXPECT_NOT_EXIST，不重复执行步骤。</li>
 * </ul>
 */
class SagaOrchestratorTest {

    private static final String DOC = "po";
    private static final String UUID = "uuid-001";
    private static final String SAP_ACTION = "http://mock/api/v1/mock/tx/sap/action";
    private static final String SAP_COMPENSATE = "http://mock/api/v1/mock/tx/sap/compensate";
    private static final String APS_ACTION = "http://mock/api/v1/mock/tx/aps/action";
    private static final String APS_COMPENSATE = "http://mock/api/v1/mock/tx/aps/compensate";

    private InMemoryTablestore template;
    private TablestoreSagaStore sagaStore;
    private SagaStepHttpClient stepClient;
    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        sagaStore = new TablestoreSagaStore(template);
        stepClient = mock(SagaStepHttpClient.class);
        orchestrator = new SagaOrchestrator(sagaStore, stepClient,
                new SagaProperties(), new ObjectMapper());
    }

    /** 3 步骤计划：SAP → APS → 通知（验收冒烟同构） */
    private TxPlan plan() {
        return new TxPlan(DOC, 100L, 10L, List.of(
                new TxPlan.TxStep(1, "SAP", SAP_ACTION, SAP_COMPENSATE, 5000L, 0),
                new TxPlan.TxStep(2, "APS", APS_ACTION, APS_COMPENSATE, 5000L, 0),
                new TxPlan.TxStep(3, "notify", "http://mock/api/v1/mock/tx/notify/action",
                        null, 5000L, 0)));
    }

    /** 步骤端点响应路由：按 url 命中失败集合返回 500 failed，其余 200 */
    private void stubEndpoints(String... failedUrls) {
        List<String> failed = List.of(failedUrls);
        List<String> callOrder = new ArrayList<>();
        when(stepClient.post(anyString(), anyString(), any(TxPlan.TxStep.class), anyString()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    callOrder.add(url);
                    return failed.contains(url)
                            ? new StepResponse(500, "{\"error\":\"injected\"}", true)
                            : new StepResponse(200, "{\"ok\":true}", false);
                });
    }

    @Test
    @DisplayName("正向按 seq 顺序执行：3 步成功 → ACTIONS_SUCCEEDED，提交后 markCommitted → COMPLETED")
    void forward_allStepsSuccess_actionsSucceededThenCompleted() {
        stubEndpoints();

        SagaResult result = orchestrator.execute(plan(), DOC, UUID, 1L, 100L, 10L,
                "alice", "key-forward-1");

        assertThat(result.status()).isEqualTo(SagaStatus.ACTIONS_SUCCEEDED);
        assertThat(result.sagaId()).startsWith("saga_");
        // 顺序执行：SAP → APS → notify（verify 顺序断言）
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(stepClient);
        inOrder.verify(stepClient).post(org.mockito.ArgumentMatchers.eq(SAP_ACTION),
                anyString(), any(TxPlan.TxStep.class), anyString());
        inOrder.verify(stepClient).post(org.mockito.ArgumentMatchers.eq(APS_ACTION),
                anyString(), any(TxPlan.TxStep.class), anyString());
        // 外部步骤完成但本地未提交：不能是 COMPLETED
        assertThat(sagaStore.find(result.sagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.ACTIONS_SUCCEEDED);
        assertThat(sagaStore.find(result.sagaId()).orElseThrow().getExpectedVersion())
                .isEqualTo(1L);

        assertThat(orchestrator.markCommitted(result.sagaId())).isEqualTo(SagaStatus.COMPLETED);
        assertThat(sagaStore.find(result.sagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("APS 失败 → SAP 逆序冲正 → COMPENSATED，单据不改状态（TX-02 验收场景）")
    void apsFailed_sapCompensated_reverseOrder() {
        stubEndpoints(APS_ACTION);

        assertThatThrownBy(() -> orchestrator.execute(plan(), DOC, UUID, 1L, 100L, 10L,
                "alice", "key-compensate-1"))
                .isInstanceOf(SagaCompensatedException.class);

        // SAP 冲正端点被调用（notify 未执行不冲正；APS 失败步本身不冲正）
        verify(stepClient, times(1)).post(org.mockito.ArgumentMatchers.eq(SAP_COMPENSATE),
                anyString(), any(TxPlan.TxStep.class), anyString());
        verify(stepClient, times(0)).post(org.mockito.ArgumentMatchers.eq(APS_COMPENSATE),
                anyString(), any(TxPlan.TxStep.class), anyString());
        // 执行记录终态 COMPENSATED
        String sagaId = SagaOrchestrator.deriveSagaId("key-compensate-1", UUID, 10L);
        assertThat(sagaStore.find(sagaId).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);
        // 未产生死信（null=不过滤 handle_status，全量计数）
        assertThat(sagaStore.countDeadLetters(null)).isZero();
    }

    @Test
    @DisplayName("冲正失败 → DEAD + saga_dead_letter 落表 + ERROR 告警（TX-03）")
    void compensateFailed_deadLetterPersisted() {
        stubEndpoints(APS_ACTION, SAP_COMPENSATE);

        assertThatThrownBy(() -> orchestrator.execute(plan(), DOC, UUID, 1L, 100L, 10L,
                "alice", "key-dead-1"))
                .isInstanceOf(SagaDeadException.class);

        String sagaId = SagaOrchestrator.deriveSagaId("key-dead-1", UUID, 10L);
        assertThat(sagaStore.find(sagaId).orElseThrow().getStatus()).isEqualTo(SagaStatus.DEAD);
        assertThat(sagaStore.countDeadLetters(null)).isEqualTo(1);
        assertThat(sagaStore.findDeadLetter(sagaId)).isPresent();
        assertThat(sagaStore.findDeadLetter(sagaId).orElseThrow().getFailedStepSeq()).isEqualTo(2L);
    }

    @Test
    @DisplayName("本地提交失败后的同步冲正：全部已成功步骤逆序冲正 → COMPENSATED（TX-03 收口）")
    void compensateAfterCommitFailure_reversesSucceededSteps() {
        stubEndpoints();

        SagaResult result = orchestrator.execute(plan(), DOC, UUID, 1L, 100L, 10L,
                "alice", "key-commit-fail-1");
        assertThat(result.status()).isEqualTo(SagaStatus.ACTIONS_SUCCEEDED);

        SagaStatus status = orchestrator.compensateAfterCommitFailure(result.sagaId());

        assertThat(status).isEqualTo(SagaStatus.COMPENSATED);
        // 逆序：APS（seq2）先于 SAP（seq1）；notify 无冲正端点跳过
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(stepClient);
        inOrder.verify(stepClient).post(org.mockito.ArgumentMatchers.eq(APS_COMPENSATE),
                anyString(), any(TxPlan.TxStep.class), anyString());
        inOrder.verify(stepClient).post(org.mockito.ArgumentMatchers.eq(SAP_COMPENSATE),
                anyString(), any(TxPlan.TxStep.class), anyString());
        assertThat(sagaStore.find(result.sagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);

        // 幂等：再次调用不重复冲正
        assertThat(orchestrator.compensateAfterCommitFailure(result.sagaId()))
                .isEqualTo(SagaStatus.COMPENSATED);
        verify(stepClient, times(1)).post(org.mockito.ArgumentMatchers.eq(SAP_COMPENSATE),
                anyString(), any(TxPlan.TxStep.class), anyString());
    }

    @Test
    @DisplayName("幂等：同幂等键重放命中 EXECUTION 记录，步骤不重复执行（EXPECT_NOT_EXIST）")
    void idempotentReplay_noDoubleExecution() {
        stubEndpoints();

        SagaResult first = orchestrator.execute(plan(), DOC, UUID, 1L, 100L, 10L,
                "alice", "key-replay-1");
        assertThat(first.status()).isEqualTo(SagaStatus.ACTIONS_SUCCEEDED);
        orchestrator.markCommitted(first.sagaId());
        // 3 步骤各一次
        verify(stepClient, times(3)).post(anyString(), anyString(),
                any(TxPlan.TxStep.class), anyString());

        // 同幂等键重放：同 saga_id，回放终态，不再出站调用
        SagaResult second = orchestrator.execute(plan(), DOC, UUID, 1L, 100L, 10L,
                "alice", "key-replay-1");
        assertThat(second.sagaId()).isEqualTo(first.sagaId());
        assertThat(second.status()).isEqualTo(SagaStatus.COMPLETED);
        verify(stepClient, times(3)).post(anyString(), anyString(),
                any(TxPlan.TxStep.class), anyString());
    }

    @Test
    @DisplayName("saga_id 派生：幂等键一致则 saga_id 一致，缺省键每次不同")
    void deriveSagaId_semantics() {
        String a = SagaOrchestrator.deriveSagaId("same-key", UUID, 10L);
        String b = SagaOrchestrator.deriveSagaId("same-key", UUID, 10L);
        assertThat(a).isEqualTo(b).startsWith("saga_");
        String noKey1 = SagaOrchestrator.deriveSagaId(null, UUID, 10L);
        String noKey2 = SagaOrchestrator.deriveSagaId(null, UUID, 10L);
        assertThat(noKey1).isNotEqualTo(noKey2);
    }
}
