package com.example.common.infrastructure.tablestore.ots;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.model.search.CreateSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.ListSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.ListSearchIndexResponse;
import com.alicloud.openservices.tablestore.model.search.SearchIndexInfo;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OtsTablestoreTemplate.ensureSearchIndex 索引 TTL 对齐测试（任务 #5）：
 *
 * <p>OTS 约束「索引 TTL ≤ 表 TTL」——TTL 表（audit_log/event_outbox）创建索引时
 * 若不显式设置 IndexTTL，SDK 默认 forever 会被服务端拒绝（OTSParameterInvalid：
 * search index ttl must be less than or equal table ttl）。本测试以 mock SyncClient
 * 捕获 CreateSearchIndexRequest，验证索引 TTL 与表 TTL 对齐语义。</p>
 */
class OtsTablestoreTemplateSearchIndexTtlTest {

    private SyncClient client;
    private TableMetadataRegistry registry;
    private OtsTablestoreTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(SyncClient.class);
        registry = new TableMetadataRegistry();
        template = new OtsTablestoreTemplate(client, registry);
    }

    @Test
    @DisplayName("TTL 表建索引：CreateSearchIndexRequest 携带与表一致的索引 TTL（秒）")
    void ensureSearchIndex_ttlTable_indexTtlAlignedWithTable() {
        // audit_log 定义：ttlSeconds=34560000（400 天，V1 定义口径）
        TableDefinition definition = newDefinition("audit_log", 34560000L);
        registry.register(definition);
        stubNoExistingIndex();

        template.ensureSearchIndex("audit_log", definition.getSearchIndexes().get(0));

        CreateSearchIndexRequest request = captureCreateRequest();
        assertThat(request.getTableName()).isEqualTo("audit_log");
        assertThat(request.getIndexName()).isEqualTo("audit_log_index");
        assertThat(request.getTimeToLive())
                .as("索引 TTL 必须等于表 TTL（秒），否则违反 OTS「索引 TTL ≤ 表 TTL」约束")
                .isEqualTo(34560000);
    }

    @Test
    @DisplayName("event_outbox 短 TTL 表：索引 TTL 对齐 30 天（2592000 秒）")
    void ensureSearchIndex_outboxTable_indexTtlAligned() {
        TableDefinition definition = newDefinition("event_outbox", 2592000L);
        registry.register(definition);
        stubNoExistingIndex();

        template.ensureSearchIndex("event_outbox", definition.getSearchIndexes().get(0));

        assertThat(captureCreateRequest().getTimeToLive()).isEqualTo(2592000);
    }

    @Test
    @DisplayName("非 TTL 表建索引：不设置索引 TTL（保持 SDK 默认 forever）")
    void ensureSearchIndex_foreverTable_indexTtlUntouched() {
        TableDefinition definition = newDefinition("entity_schema", TableDefinition.TTL_FOREVER);
        registry.register(definition);
        stubNoExistingIndex();

        template.ensureSearchIndex("entity_schema", definition.getSearchIndexes().get(0));

        assertThat(captureCreateRequest().getTimeToLive())
                .as("无 TTL 表不设置索引 TTL，沿用 SDK 默认 forever")
                .isNull();
    }

    @Test
    @DisplayName("幂等语义保持：索引已存在时直接返回，不再调用 CreateSearchIndex")
    void ensureSearchIndex_alreadyExists_idempotent() {
        TableDefinition definition = newDefinition("audit_log", 34560000L);
        registry.register(definition);
        ListSearchIndexResponse listResponse = mock(ListSearchIndexResponse.class);
        SearchIndexInfo info = mock(SearchIndexInfo.class);
        when(info.getIndexName()).thenReturn("audit_log_index");
        when(listResponse.getIndexInfos()).thenReturn(List.of(info));
        when(client.listSearchIndex(any(ListSearchIndexRequest.class))).thenReturn(listResponse);

        template.ensureSearchIndex("audit_log", definition.getSearchIndexes().get(0));

        verify(client, never()).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    // ==================== 辅助 ====================

    private void stubNoExistingIndex() {
        ListSearchIndexResponse listResponse = mock(ListSearchIndexResponse.class);
        when(listResponse.getIndexInfos()).thenReturn(List.of());
        when(client.listSearchIndex(any(ListSearchIndexRequest.class))).thenReturn(listResponse);
    }

    private CreateSearchIndexRequest captureCreateRequest() {
        ArgumentCaptor<CreateSearchIndexRequest> captor =
                ArgumentCaptor.forClass(CreateSearchIndexRequest.class);
        verify(client).createSearchIndex(captor.capture());
        return captor.getValue();
    }

    private static TableDefinition newDefinition(String tableName, long ttlSeconds) {
        TableDefinition definition = new TableDefinition();
        definition.setName(tableName);
        definition.setTtlSeconds(ttlSeconds);
        definition.setSearchIndexes(List.of(new SearchIndexDef(tableName + "_index",
                List.of(new IndexFieldDef("operator", IndexFieldDef.TYPE_KEYWORD)))));
        return definition;
    }
}
