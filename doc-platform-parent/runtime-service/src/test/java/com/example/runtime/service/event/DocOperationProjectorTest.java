package com.example.runtime.service.event;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocOperationLogStore;
import com.example.runtime.domain.event.DocProjectionStore;
import com.example.runtime.domain.model.DocOperationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 操作日志投影器：同步投影、扫描续做、重放、失败退避/转人工。 */
class DocOperationProjectorTest {

    private DocOperationLogStore logStore;
    private DocProjectionStore projectionStore;
    private OutboxRelayService relay;
    private DocOperationProjector projector;

    @BeforeEach
    void setUp() {
        logStore = mock(DocOperationLogStore.class);
        projectionStore = mock(DocProjectionStore.class);
        relay = mock(OutboxRelayService.class);
        projector = new DocOperationProjector(logStore, projectionStore, relay);
    }

    private DocOperationLog log(String operation, boolean event) {
        DocOperationLog log = new DocOperationLog();
        log.setPartitionKey(PartitionKeys.dataKey("po", "u"));
        log.setLogUuid("op:u:" + operation);
        log.setOperationId(operation);
        log.setDocCode("po");
        log.setDocUuid("u");
        log.setOperation(operation);
        log.setEventType(event ? "DOC_STATUS_CHANGED" : null);
        log.setProjectionStatus(DocOperationLog.PROJECTION_PENDING);
        return log;
    }

    @Test
    @DisplayName("同步投影：历史+outbox+审计成功后才标记完成并唤醒中继")
    void projectNow_success() {
        DocOperationLog log = log(DocOperationContext.OP_TRANSITION, true);
        when(logStore.find(PartitionKeys.dataKey("po", "u"), "op:u:transition")).thenReturn(java.util.Optional.of(log));
        when(logStore.claim(eq(log), anyString(), anyLong(), anyLong())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThat(
                projector.projectNow("po", "u", "transition")).isTrue();

        verify(projectionStore).putHistoryIfAbsent(log);
        verify(projectionStore).putOutboxIfAbsent(log);
        verify(projectionStore).putAuditIfAbsent(log);
        verify(logStore).markProjected(eq(log), anyString());
        verify(relay).enqueueDoc(PartitionKeys.dataKey("po", "u"), "u");
    }

    @Test
    @DisplayName("已完成日志：直接返回，不重复认领/投影")
    void projectNow_alreadyDone() {
        DocOperationLog log = log(DocOperationContext.OP_UPDATE, false);
        log.setProjectionStatus(DocOperationLog.PROJECTION_DONE);
        when(logStore.find(PartitionKeys.dataKey("po", "u"), "op:u:update")).thenReturn(java.util.Optional.of(log));

        org.assertj.core.api.Assertions.assertThat(
                projector.projectNow("po", "u", "update")).isTrue();
        verify(logStore, never()).claim(any(), anyString(), anyLong(), anyLong());
        verify(projectionStore, never()).putAuditIfAbsent(any());
    }

    @Test
    @DisplayName("扫描续做：认领失败跳过；成功后推进")
    void projectPending_claimsAndProjects() {
        DocOperationLog skipped = log(DocOperationContext.OP_UPDATE, false);
        DocOperationLog projected = log(DocOperationContext.OP_TRANSITION, true);
        when(logStore.scanPending(anyLong(), eq(100), isNull()))
                .thenReturn(new DocOperationLogStore.ScanPage(List.of(skipped, projected), null));
        when(logStore.claim(eq(skipped), anyString(), anyLong(), anyLong())).thenReturn(false);
        when(logStore.claim(eq(projected), anyString(), anyLong(), anyLong())).thenReturn(true);

        projector.projectPending();

        verify(logStore).markProjected(eq(projected), anyString());
        verify(logStore, never()).markProjected(eq(skipped), anyString());
    }

    @Test
    @DisplayName("投影异常：按尝试次数退避；达到上限转人工")
    void projectNow_retryThenManual() {
        DocOperationLog log = log(DocOperationContext.OP_UPDATE, false);
        log.setProjectionAttempts(7L);
        when(logStore.find(PartitionKeys.dataKey("po", "u"), "op:u:update")).thenReturn(java.util.Optional.of(log));
        when(logStore.claim(eq(log), anyString(), anyLong(), anyLong())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(projectionStore).putAuditIfAbsent(log);

        org.assertj.core.api.Assertions.assertThat(
                projector.projectNow("po", "u", "update")).isFalse();
        verify(logStore).markProjectionFailure(eq(log), anyString(), anyString(),
                anyLong(), eq(true));
        verify(logStore, never()).markProjected(any(), anyString());
    }

    @Test
    @DisplayName("重放：逐条幂等重写三类投影，不推进状态")
    void replay_rewritesPayloads() {
        DocOperationLog create = log(DocOperationContext.OP_CREATE, true);
        DocOperationLog update = log(DocOperationContext.OP_UPDATE, false);
        when(logStore.scanByDoc("po", "u", 0L, 100L, 100))
                .thenReturn(List.of(create, update));

        org.assertj.core.api.Assertions.assertThat(projector.replay("po", "u", 0L, 100L))
                .isEqualTo(2);
        verify(projectionStore).putHistoryIfAbsent(create);
        verify(projectionStore).putOutboxIfAbsent(create);
        verify(projectionStore).putAuditIfAbsent(create);
        verify(projectionStore).putAuditIfAbsent(update);
        verify(projectionStore, never()).putHistoryIfAbsent(update);
    }
}
