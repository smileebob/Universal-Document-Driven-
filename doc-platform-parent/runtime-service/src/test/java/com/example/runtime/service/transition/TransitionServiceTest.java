package com.example.runtime.service.transition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.DocOperationContext;
import com.example.runtime.domain.model.DocStateMachine;
import com.example.runtime.domain.port.DocConfigGateway;
import com.example.runtime.domain.port.DocDataStore;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.event.DocOperationProjector;
import com.example.runtime.service.event.OutboxRelayService;
import com.example.runtime.service.rule.DocRuleChecker;
import com.example.runtime.service.saga.SagaOrchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 流转编排：在途锁、Saga 收口/补偿、本地提交冲突收口。 */
class TransitionServiceTest {

    private static final String DOC = "po";
    private static final String UUID = "u-1";

    private DocDataStore docDataStore;
    private SagaOrchestrator sagaOrchestrator;
    private DocOperationProjector projector;
    private OutboxRelayService relay;
    private TransitionService service;
    private TxPlan plan;

    @BeforeEach
    void setUp() throws InterruptedException {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        DocConfigGateway configGateway = mock(DocConfigGateway.class);
        DocDefinition definition = mock(DocDefinition.class);
        when(definition.hasStatus(10L)).thenReturn(true);
        when(definition.stateName(anyLong())).thenReturn("状态");
        when(configGateway.getDocSchema(DOC)).thenReturn(definition);
        DocStateMachine stateMachine = mock(DocStateMachine.class);
        when(stateMachine.isLegal(100L, 10L)).thenReturn(true);
        when(configGateway.getStateMachine(DOC)).thenReturn(stateMachine);
        plan = new TxPlan(DOC, 100L, 10L, List.of(
                new TxPlan.TxStep(1, "step", "http://mock", "http://mock/c", 1000L, 0)));
        when(configGateway.getTxPlans(DOC)).thenReturn(List.of(plan));

        DocRuleChecker ruleChecker = mock(DocRuleChecker.class);
        when(ruleChecker.checkTransition(anyString(), anyString(), anyLong(), anyLong(),
                any(), any(), anyString())).thenReturn(List.of());

        docDataStore = mock(DocDataStore.class);
        long now = System.currentTimeMillis();
        when(docDataStore.find(DOC, UUID)).thenReturn(Optional.of(new DocDataRecord(
                UUID, DOC, 100L, 1L, Map.of("a", "b"), List.of(), false,
                "alice", "dept", now, now)));
        sagaOrchestrator = mock(SagaOrchestrator.class);
        when(sagaOrchestrator.commitLeaseMs()).thenReturn(60_000L);
        projector = mock(DocOperationProjector.class);
        relay = mock(OutboxRelayService.class);
        service = new TransitionService(redisson, configGateway, docDataStore, ruleChecker,
                mock(AuditService.class), sagaOrchestrator, relay, projector);
    }

    @Test
    @DisplayName("Saga 成功：在途锁版本作为提交快照，提交后 markPending/markCommitted/投影")
    void sagaSuccess_commitsAfterActions() {
        when(docDataStore.acquireOperation(anyString(), anyString(), eq(1L), eq(100L),
                anyString(), anyString(), anyLong())).thenReturn(2L);
        when(sagaOrchestrator.execute(anyString(), any(), eq(DOC), eq(UUID), eq(2L),
                eq(100L), eq(10L), eq("alice")))
                .thenReturn(new SagaOrchestrator.SagaResult("saga", SagaStatus.ACTIONS_SUCCEEDED));
        when(docDataStore.commitTransition(eq(DOC), eq(UUID), eq(2L), eq(100L), eq(10L),
                any(DocOperationContext.class)))
                .thenReturn(new DocDataStore.TransitionOutcome(3L, 3L, "evt", 2L));

        Map<String, Object> result = service.transition(DOC, UUID, 10L, "submit",
                "alice", "key-1");

        assertThat(result.get("version")).isEqualTo(3L);
        assertThat(result.get("saga_triggered")).isEqualTo(true);
        verify(sagaOrchestrator).markCommitPending(anyString());
        verify(sagaOrchestrator).markCommitted(anyString());
        verify(projector).projectNow(eq(DOC), eq(UUID), anyString());
        verify(relay).enqueue(eq(com.example.common.infrastructure.tablestore.util
                .PartitionKeys.dataKey(DOC, UUID)), eq("evt"));
    }

    @Test
    @DisplayName("在途锁获取失败：40901，不执行外部步骤")
    void acquireFailed_conflict() {
        when(docDataStore.acquireOperation(anyString(), anyString(), eq(1L), eq(100L),
                anyString(), anyString(), anyLong())).thenReturn(0L);

        assertThatThrownBy(() -> service.transition(DOC, UUID, 10L, "submit",
                "alice", "key-2"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
        verify(sagaOrchestrator, never()).execute(anyString(), any(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong(), anyString());
        verify(docDataStore, never()).releaseOperation(anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("Saga 冲正成功：释放锁并返回 42202")
    void sagaCompensated_releasesLock() {
        when(docDataStore.acquireOperation(anyString(), anyString(), eq(1L), eq(100L),
                anyString(), anyString(), anyLong())).thenReturn(2L);
        when(sagaOrchestrator.execute(anyString(), any(), anyString(), anyString(), eq(2L),
                eq(100L), eq(10L), anyString()))
                .thenThrow(new SagaOrchestrator.SagaCompensatedException("saga", "failed"));

        assertThatThrownBy(() -> service.transition(DOC, UUID, 10L, "submit",
                "alice", "key-3"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(42202);
        verify(docDataStore).releaseOperation(eq(DOC), eq(UUID), anyString(), anyLong());
    }

    @Test
    @DisplayName("Saga 结果未知：保留在途锁，返回人工处置")
    void sagaManual_keepsLock() {
        when(docDataStore.acquireOperation(anyString(), anyString(), eq(1L), eq(100L),
                anyString(), anyString(), anyLong())).thenReturn(2L);
        when(sagaOrchestrator.execute(anyString(), any(), anyString(), anyString(), eq(2L),
                eq(100L), eq(10L), anyString()))
                .thenThrow(new SagaOrchestrator.SagaDeadException("saga", "unknown",
                        SagaStatus.MANUAL_REQUIRED));

        assertThatThrownBy(() -> service.transition(DOC, UUID, 10L, "submit",
                "alice", "key-4"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(42202);
        verify(docDataStore, never()).releaseOperation(anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("本地提交冲突且外部步骤冲正成功：释放锁并返回 40901")
    void commitConflict_compensated_releasesLock() {
        when(docDataStore.acquireOperation(anyString(), anyString(), eq(1L), eq(100L),
                anyString(), anyString(), anyLong())).thenReturn(2L);
        when(sagaOrchestrator.execute(anyString(), any(), anyString(), anyString(), eq(2L),
                eq(100L), eq(10L), anyString()))
                .thenReturn(new SagaOrchestrator.SagaResult("saga", SagaStatus.ACTIONS_SUCCEEDED));
        when(docDataStore.commitTransition(anyString(), anyString(), anyLong(), anyLong(),
                anyLong(), any(DocOperationContext.class)))
                .thenThrow(new TsConditionCheckException("conflict"));
        when(sagaOrchestrator.compensateAfterCommitFailure(anyString()))
                .thenReturn(SagaStatus.COMPENSATED);

        assertThatThrownBy(() -> service.transition(DOC, UUID, 10L, "submit",
                "alice", "key-5"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
        verify(docDataStore).releaseOperation(eq(DOC), eq(UUID), anyString(), anyLong());
    }
}
