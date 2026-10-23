package com.example.meta.infrastructure.index;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexState;
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

/** 在线索引发布：候选 brother 创建 → 同步 → switchIndexName，不删除在线逻辑索引。 */
class OnlineIndexPublisherTest {

    private static final String TABLE = "doc_data";
    private static final String LOGICAL = "doc_data_index";
    private static final String BROTHER = LOGICAL + "_reindex";

    private TablestoreTemplate template;
    private IndexPublishTaskStore taskStore;
    private OnlineIndexPublisher publisher;
    private SearchIndexDef definition;

    @BeforeEach
    void setUp() {
        template = mock(TablestoreTemplate.class);
        taskStore = mock(IndexPublishTaskStore.class);
        publisher = new OnlineIndexPublisher(template, taskStore, new ObjectMapper());
        definition = new SearchIndexDef(LOGICAL, List.of(
                new IndexFieldDef("doc_code", IndexFieldDef.TYPE_KEYWORD),
                new IndexFieldDef("deleted", IndexFieldDef.TYPE_LONG)));
    }

    @Test
    @DisplayName("字段已覆盖：直接 READY，不创建候选/不切流")
    void covered_noSwitch() {
        when(template.isSearchIndexCovered(TABLE, definition)).thenReturn(true);

        IndexPublishTask task = publisher.ensure(TABLE, definition);

        assertThat(task.getStatus()).isEqualTo(IndexPublishTask.STATUS_READY);
        verify(template, never()).createBrotherSearchIndex(anyString(), anyString(), any());
        verify(template, never()).switchSearchIndex(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("字段缺失：创建 {source}_reindex 兄弟索引，等待就绪后切流并保留旧索引")
    void missingFields_createsBrotherAndSwitches() {
        when(template.isSearchIndexCovered(TABLE, definition)).thenReturn(false);
        when(template.describeSearchIndex(TABLE, LOGICAL)).thenReturn(
                new SearchIndexState(LOGICAL, true, "RUNNING", "FULL", 1L, null),
                new SearchIndexState(LOGICAL, true, "RUNNING", "FULL", 2L, BROTHER));
        when(template.describeSearchIndex(TABLE, BROTHER)).thenReturn(
                new SearchIndexState(BROTHER, false, null, null, null, null),
                new SearchIndexState(BROTHER, true, "RUNNING", "FULL", 2L, null));

        IndexPublishTask task = publisher.ensure(TABLE, definition);

        assertThat(task.getStatus()).isEqualTo(IndexPublishTask.STATUS_SWITCHED);
        assertThat(task.getCandidateIndex()).isEqualTo(BROTHER);
        assertThat(task.getPreviousIndex()).isEqualTo(BROTHER);
        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).createBrotherSearchIndex(eq(TABLE), eq(LOGICAL), captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo(BROTHER);
        verify(template).switchSearchIndex(TABLE, LOGICAL, BROTHER);
        // 在线逻辑索引从未被删除
        verify(template, never()).deleteSearchIndexIfExists(TABLE, LOGICAL);
    }

    @Test
    @DisplayName("候选创建失败：任务 FAILED，旧索引保持在线（不切流）")
    void candidateFailure_keepsOldIndex() {
        when(template.isSearchIndexCovered(TABLE, definition)).thenReturn(false);
        when(template.describeSearchIndex(TABLE, LOGICAL)).thenReturn(
                new SearchIndexState(LOGICAL, true, "RUNNING", "FULL", 1L, null));
        when(template.describeSearchIndex(TABLE, BROTHER)).thenReturn(
                new SearchIndexState(BROTHER, false, null, null, null, null));
        org.mockito.Mockito.doThrow(new RuntimeException("create failed"))
                .when(template).createBrotherSearchIndex(eq(TABLE), eq(LOGICAL), any());

        assertThatThrownBy(() -> publisher.ensure(TABLE, definition))
                .isInstanceOf(OnlineIndexPublisher.IndexPublishException.class);
        verify(template, never()).switchSearchIndex(anyString(), anyString(), anyString());
        ArgumentCaptor<IndexPublishTask> captor = ArgumentCaptor.forClass(IndexPublishTask.class);
        verify(taskStore, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus())
                .isEqualTo(IndexPublishTask.STATUS_FAILED);
    }
}
