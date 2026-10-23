package com.example.runtime.service.saga;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.runtime.domain.saga.SagaDeadLetter;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.infrastructure.saga.TablestoreSagaStore;
import com.example.runtime.service.saga.SagaOrchestrator.SagaDeadException;
import com.example.runtime.service.saga.SagaStepHttpClient.StepResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saga 恢复/补偿续推/死信重试/提交中间态分支。 */
class SagaOrchestratorRecoveryTest {

    private static final String DOC = "po";
    private static final String UUID = "uuid-recovery";
    private static final String SAGA = "saga_recovery";
    private static final String ACTION = "http://mock/action";
    private static final String COMPENSATE = "http://mock/compensate";

    private final ObjectMapper mapper = new ObjectMapper();
    private TablestoreSagaStore sagaStore;
    private SagaStepHttpClient stepClient;
    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        InMemoryTablestore template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        sagaStore = new TablestoreSagaStore(template);
        stepClient = mock(SagaStepHttpClient.class);
        orchestrator = new SagaOrchestrator(sagaStore, stepClient,
                new SagaProperties(), mapper);
    }

    private TxPlan oneStepPlan() {
        return new TxPlan(DOC, 100L, 10L, List.of(
                new TxPlan.TxStep(1, "external", ACTION, COMPENSATE, 1000L, 0)));
    }

    private SagaExecution store(SagaStatus status, String logsJson) throws Exception {
        SagaExecution execution = new SagaExecution();
        execution.setSagaId(SAGA);
        execution.setDocCode(DOC);
        execution.setDocUuid(UUID);
        execution.setFromStatus(100L);
        execution.setToStatus(10L);
        execution.setExpectedVersion(1L);
        execution.setStatus(status);
        execution.setCurrentStepSeq(1L);
        execution.setStepLogs(logsJson);
        execution.setTxPlanJson(mapper.writeValueAsString(oneStepPlan()));
        execution.setOperator("alice");
        execution.setCreateTime(System.currentTimeMillis() - 1000);
        execution.setUpdateTime(System.currentTimeMillis() - 1000);
        sagaStore.createIfAbsent(execution);
        return execution;
    }

    private void stubCompensate(boolean success, boolean unknown) {
        when(stepClient.post(eq(COMPENSATE), anyString(), any(TxPlan.TxStep.class), anyString()))
                .thenReturn(success
                        ? new StepResponse(200, "{}", false)
                        : new StepResponse(unknown ? 500 : 400, "{}", true));
    }

    @Test
    @DisplayName("EXECUTING 最近一步 UNKNOWN：不重复执行，转补偿")
    void resumeStuck_unknown_compensates() throws Exception {
        SagaExecution execution = store(SagaStatus.EXECUTING,
                mapper.writeValueAsString(List.of(Map.of("seq", 1, "phase", "action",
                        "status", "UNKNOWN", "result", false))));
        stubCompensate(true, false);

        orchestrator.resumeStuck(execution);
        assertThat(sagaStore.find(SAGA).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);
        verify(stepClient).post(eq(COMPENSATE), anyString(),
                any(TxPlan.TxStep.class), anyString());
    }

    @Test
    @DisplayName("COMPENSATING 续推：补完未完成补偿 → COMPENSATED")
    void resumeCompensation_completes() throws Exception {
        SagaExecution execution = store(SagaStatus.COMPENSATING,
                mapper.writeValueAsString(List.of(Map.of("seq", 1, "phase", "action",
                        "status", "SUCCESS", "result", true))));
        stubCompensate(true, false);

        orchestrator.resumeCompensation(execution);
        assertThat(sagaStore.find(SAGA).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("死信重试成功：补完冲正 → COMPENSATED + handle_status=1")
    void retryDeadLetter_success() throws Exception {
        store(SagaStatus.DEAD,
                mapper.writeValueAsString(List.of(Map.of("seq", 1, "phase", "action",
                        "status", "SUCCESS", "result", true))));
        SagaDeadLetter letter = deadLetter();
        stubCompensate(true, false);

        orchestrator.retryDeadLetter(letter, "admin");

        assertThat(sagaStore.find(SAGA).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);
        assertThat(sagaStore.findDeadLetter(SAGA).orElseThrow().getHandleStatus())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("死信重试失败：保持 DEAD，死信仍待处理")
    void retryDeadLetter_failure() throws Exception {
        store(SagaStatus.DEAD,
                mapper.writeValueAsString(List.of(Map.of("seq", 1, "phase", "action",
                        "status", "SUCCESS", "result", true))));
        SagaDeadLetter letter = deadLetter();
        stubCompensate(false, false);

        assertThatThrownBy(() -> orchestrator.retryDeadLetter(letter, "admin"))
                .isInstanceOf(SagaDeadException.class);
        assertThat(sagaStore.findDeadLetter(SAGA).orElseThrow().getHandleStatus())
                .isZero();
    }

    @Test
    @DisplayName("COMMIT_PENDING：提交失败后仍可补偿收口")
    void compensateAfterCommitFailure_commitPending() throws Exception {
        store(SagaStatus.COMMIT_PENDING,
                mapper.writeValueAsString(List.of(Map.of("seq", 1, "phase", "action",
                        "status", "SUCCESS", "result", true))));
        stubCompensate(true, false);
        assertThat(orchestrator.compensateAfterCommitFailure(SAGA))
                .isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("COMPLETED：不再冲正")
    void compensateAfterCommitFailure_completed() throws Exception {
        store(SagaStatus.COMPLETED,
                mapper.writeValueAsString(List.of(Map.of("seq", 1, "phase", "action",
                        "status", "SUCCESS", "result", true))));
        assertThat(orchestrator.compensateAfterCommitFailure(SAGA))
                .isEqualTo(SagaStatus.COMPLETED);
        verify(stepClient, never()).post(anyString(), anyString(),
                any(TxPlan.TxStep.class), anyString());
    }

    @Test
    @DisplayName("COMMIT_PENDING 标记与收口；commitLeaseMs/status 查询")
    void markPending_andLease() throws Exception {
        store(SagaStatus.ACTIONS_SUCCEEDED, "[]");
        assertThat(orchestrator.status(SAGA)).isEqualTo(SagaStatus.ACTIONS_SUCCEEDED);
        assertThat(orchestrator.markCommitPending(SAGA)).isEqualTo(SagaStatus.COMMIT_PENDING);
        assertThat(orchestrator.markCommitPending(SAGA)).isEqualTo(SagaStatus.COMMIT_PENDING);
        assertThat(orchestrator.markCommitted(SAGA)).isEqualTo(SagaStatus.COMPLETED);
        assertThat(orchestrator.commitLeaseMs()).isGreaterThan(60_000L);
    }

    @Test
    @DisplayName("恢复时计划快照缺失：MANUAL_REQUIRED + 死信，不伪造补偿")
    void resumeStuck_missingPlan_manual() {
        SagaExecution execution = new SagaExecution();
        execution.setSagaId(SAGA);
        execution.setDocCode(DOC);
        execution.setDocUuid(UUID);
        execution.setStatus(SagaStatus.EXECUTING);
        execution.setCurrentStepSeq(1L);
        execution.setUpdateTime(System.currentTimeMillis() - 1000);
        sagaStore.createIfAbsent(execution);

        orchestrator.resumeStuck(execution);

        assertThat(sagaStore.find(SAGA).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.MANUAL_REQUIRED);
        assertThat(sagaStore.findDeadLetter(SAGA)).isPresent();
        verify(stepClient, never()).post(anyString(), anyString(),
                any(TxPlan.TxStep.class), anyString());
    }

    private SagaDeadLetter deadLetter() {
        SagaDeadLetter letter = new SagaDeadLetter();
        letter.setSagaId(SAGA);
        letter.setDocCode(DOC);
        letter.setDocUuid(UUID);
        letter.setFromStatus(100L);
        letter.setToStatus(10L);
        letter.setFailedStepSeq(1L);
        letter.setFailPhase("compensate");
        letter.setErrorReason("failed");
        letter.setHandleStatus(0L);
        letter.setCreateTime(System.currentTimeMillis());
        sagaStore.saveDeadLetter(letter);
        return letter;
    }
}
