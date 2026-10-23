package com.example.runtime.service.event;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.runtime.domain.event.EventOutboxStore;
import com.example.runtime.domain.event.OutboxEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 已发送 outbox 清理任务：开关、dry-run、正式删除。 */
class EventArchiveTaskTest {

    private EventOutboxStore outboxStore;
    private EventArchiveTask task;

    @BeforeEach
    void setUp() {
        outboxStore = mock(EventOutboxStore.class);
        task = new EventArchiveTask(outboxStore);
    }

    private OutboxEvent event() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId("e1");
        event.setPartitionKey("p");
        event.setSendStatus(1L);
        return event;
    }

    @Test
    @DisplayName("开关关闭：不扫描")
    void disabled_noScan() {
        task.archive();
        verify(outboxStore, never()).scanSent(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("dry-run：统计候选不删除")
    void dryRun_noDelete() {
        ReflectionTestUtils.setField(task, "archiveEnabled", true);
        ReflectionTestUtils.setField(task, "dryRun", true);
        when(outboxStore.scanSent(anyLong(), anyInt(), isNull()))
                .thenReturn(new EventOutboxStore.ScanPage(List.of(event()), null));

        task.archive();

        verify(outboxStore, never()).delete(any());
    }

    @Test
    @DisplayName("正式清理：删除已发送行")
    void cleanup_deletes() {
        ReflectionTestUtils.setField(task, "archiveEnabled", true);
        ReflectionTestUtils.setField(task, "dryRun", false);
        OutboxEvent event = event();
        when(outboxStore.scanSent(anyLong(), anyInt(), isNull()))
                .thenReturn(new EventOutboxStore.ScanPage(List.of(event), null));

        task.archive();

        verify(outboxStore).delete(event);
    }
}
