package com.example.runtime.service.saga;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.port.DocDataStore;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.SagaStore;

/** Saga 崩溃恢复：ACTIONS_SUCCEEDED 的补提交/补收口/冲正分支（TX-03）。 */
class SagaRecoveryTaskTest {

    private static final String DOC = "po";
    private static final String UUID = "uuid-1";
    private static final String SAGA = "saga_test";

    private SagaStore sagaStore;
    private SagaOrchestrator orchestrator;
    private DocDataStore docDataStore;
    private SagaRecoveryTask task;

    @BeforeEach
    void setUp() {
        sagaStore = mock(SagaStore.class);
        orchestrator = mock(SagaOrchestrator.class);
        docDataStore = mock(DocDataStore.class);
        SagaProperties properties = new SagaProperties();
        properties.setRecoveryEnabled(true);
        properties.setStuckThresholdMs(0L);
        task = new SagaRecoveryTask(sagaStore, orchestrator, properties, docDataStore);
    }

    private SagaExecution execution(SagaStatus status) {
        SagaExecution execution = new SagaExecution();
        execution.setSagaId(SAGA);
        execution.setDocCode(DOC);
        execution.setDocUuid(UUID);
        execution.setFromStatus(100L);
        execution.setToStatus(10L);
        execution.setExpectedVersion(1L);
        execution.setOperator("alice");
        execution.setStatus(status);
        execution.setUpdateTime(System.currentTimeMillis() - 1000);
        return execution;
    }

    private DocDataRecord doc(long status, long version) {
        long now = System.currentTimeMillis();
        return new DocDataRecord(UUID, DOC, status, version, Map.of(), List.of(),
                false, "alice", "dept", now, now);
    }

    private void onlyActionsSucceeded(SagaExecution execution) {
        when(sagaStore.scanStuckExecuting(anyLong())).thenReturn(List.of());
        when(sagaStore.scanActionsSucceeded(anyLong())).thenReturn(List.of(execution));
    }

    @Test
    @DisplayName("单据仍为起始状态且版本一致 → 恢复补提交并标记 COMPLETED")
    void pendingCommit_commitsAndMarksCompleted() {
        SagaExecution execution = execution(SagaStatus.ACTIONS_SUCCEEDED);
        onlyActionsSucceeded(execution);
        when(docDataStore.find(DOC, UUID)).thenReturn(Optional.of(doc(100L, 1L)));

        task.recover();

        verify(docDataStore).commitTransition(eq(DOC), eq(UUID), eq(1L), eq(100L), eq(10L),
                eq("alice"), eq("saga-recovery"), eq(SAGA));
        verify(orchestrator).markCommitted(SAGA);
        verify(orchestrator, never()).compensateAfterCommitFailure(anyString());
    }

    @Test
    @DisplayName("单据已到目标状态 → 只补收口，不重复提交")
    void alreadyCommitted_marksOnly() {
        SagaExecution execution = execution(SagaStatus.ACTIONS_SUCCEEDED);
        onlyActionsSucceeded(execution);
        when(docDataStore.find(DOC, UUID)).thenReturn(Optional.of(doc(10L, 2L)));

        task.recover();

        verify(orchestrator).markCommitted(SAGA);
        verify(docDataStore, never()).commitTransition(anyString(), anyString(), anyLong(),
                anyLong(), anyLong(), anyString(), anyString(), anyString());
        verify(orchestrator, never()).compensateAfterCommitFailure(anyString());
    }

    @Test
    @DisplayName("单据版本/状态已变化 → 不提交，冲正外部步骤")
    void stateChanged_compensates() {
        SagaExecution execution = execution(SagaStatus.ACTIONS_SUCCEEDED);
        onlyActionsSucceeded(execution);
        when(docDataStore.find(DOC, UUID)).thenReturn(Optional.of(doc(100L, 5L)));
        when(orchestrator.compensateAfterCommitFailure(SAGA)).thenReturn(SagaStatus.COMPENSATED);

        task.recover();

        verify(orchestrator).compensateAfterCommitFailure(SAGA);
        verify(orchestrator, never()).markCommitted(SAGA);
        verify(docDataStore, never()).commitTransition(anyString(), anyString(), anyLong(),
                anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("提交竞争失败但另一方已提交 → 二次确认后只补收口，不冲正")
    void commitConflict_butCommittedByPeer_marksOnly() {
        SagaExecution execution = execution(SagaStatus.ACTIONS_SUCCEEDED);
        onlyActionsSucceeded(execution);
        when(docDataStore.find(DOC, UUID)).thenReturn(
                Optional.of(doc(100L, 1L)), Optional.of(doc(10L, 2L)));
        when(docDataStore.commitTransition(eq(DOC), eq(UUID), eq(1L), eq(100L), eq(10L),
                eq("alice"), eq("saga-recovery"), eq(SAGA)))
                .thenThrow(new TsConditionCheckException("version conflict"));

        task.recover();

        verify(orchestrator).markCommitted(SAGA);
        verify(orchestrator, never()).compensateAfterCommitFailure(anyString());
    }

    @Test
    @DisplayName("EXECUTING 续推后变为 ACTIONS_SUCCEEDED → 同轮完成收口")
    void executingResumed_actionsSucceededThenCommitted() {
        SagaExecution executing = execution(SagaStatus.EXECUTING);
        SagaExecution succeeded = execution(SagaStatus.ACTIONS_SUCCEEDED);
        when(sagaStore.scanStuckExecuting(anyLong())).thenReturn(List.of(executing));
        when(sagaStore.find(SAGA)).thenReturn(Optional.of(succeeded));
        when(sagaStore.scanActionsSucceeded(anyLong())).thenReturn(List.of());
        when(docDataStore.find(DOC, UUID)).thenReturn(Optional.of(doc(100L, 1L)));

        task.recover();

        verify(orchestrator).resumeStuck(executing);
        verify(docDataStore).commitTransition(eq(DOC), eq(UUID), eq(1L), eq(100L), eq(10L),
                eq("alice"), eq("saga-recovery"), eq(SAGA));
        verify(orchestrator).markCommitted(SAGA);
    }
}
