package com.example.runtime.service.event;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocOperationLogStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 已投影操作日志清理：开关、dry-run、条件删除。 */
class DocOperationLogCleanupTaskTest {

    private DocOperationLogStore logStore;
    private DocOperationLogCleanupTask task;

    @BeforeEach
    void setUp() {
        logStore = mock(DocOperationLogStore.class);
        task = new DocOperationLogCleanupTask(logStore);
    }

    private DocOperationLog projected() {
        DocOperationLog log = new DocOperationLog();
        log.setOperationId("op-1");
        log.setProjectionStatus(DocOperationLog.PROJECTION_DONE);
        return log;
    }

    @Test
    @DisplayName("开关关闭：不扫描不删除")
    void disabled_noScan() {
        task.cleanup();
        verify(logStore, never()).scanProjected(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("dry-run：只统计候选，不删除")
    void dryRun_noDelete() {
        ReflectionTestUtils.setField(task, "cleanupEnabled", true);
        ReflectionTestUtils.setField(task, "dryRun", true);
        when(logStore.scanProjected(anyLong(), anyInt(), isNull()))
                .thenReturn(new DocOperationLogStore.ScanPage(List.of(projected()), null));

        task.cleanup();

        verify(logStore, never()).deleteProjected(any());
    }

    @Test
    @DisplayName("正式清理：仅对已投影行执行条件删除")
    void cleanup_deletesProjected() {
        ReflectionTestUtils.setField(task, "cleanupEnabled", true);
        ReflectionTestUtils.setField(task, "dryRun", false);
        DocOperationLog log = projected();
        when(logStore.scanProjected(anyLong(), anyInt(), isNull()))
                .thenReturn(new DocOperationLogStore.ScanPage(List.of(log), null));
        when(logStore.deleteProjected(log)).thenReturn(true);

        task.cleanup();

        verify(logStore).deleteProjected(log);
    }
}
