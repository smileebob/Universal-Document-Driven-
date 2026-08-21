package com.example.common.infrastructure.tablestore.ots;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.TableStoreException;
import com.alicloud.openservices.tablestore.model.search.CreateSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.DeleteSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.DescribeSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.DescribeSearchIndexResponse;
import com.alicloud.openservices.tablestore.model.search.FieldSchema;
import com.alicloud.openservices.tablestore.model.search.FieldType;
import com.alicloud.openservices.tablestore.model.search.IndexSchema;
import com.alicloud.openservices.tablestore.model.search.ListSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.ListSearchIndexResponse;
import com.alicloud.openservices.tablestore.model.search.SearchIndexInfo;
import com.example.common.infrastructure.tablestore.exception.TablestoreAccessException;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OtsTablestoreTemplate.reconcileSearchIndex 字段对账注册测试（任务 #7）：
 *
 * <p>真实 OTS 上 bootstrap 先建基线索引（Nested 子字段为空），发布期动态追加
 * 子字段时 ensureSearchIndex「存在即跳过」永远 no-op。reconcileSearchIndex 以
 * 「描述现有索引 → 求差集 → 已覆盖跳过 / 缺失删除重建」落地真实注册能力
 * （UpdateSearchIndex 不支持增量追加字段，SDK 5.17.4 反射核实）。</p>
 */
class OtsTablestoreTemplateReconcileSearchIndexTest {

    private static final String TABLE = "entity_data";
    private static final String INDEX = "entity_data_index";

    private SyncClient client;
    private OtsTablestoreTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(SyncClient.class);
        TableMetadataRegistry registry = new TableMetadataRegistry();
        TableDefinition definition = new TableDefinition();
        definition.setName(TABLE);
        definition.setTtlSeconds(TableDefinition.TTL_FOREVER);
        registry.register(definition);
        template = new OtsTablestoreTemplate(client, registry);
    }

    /** 期望定义：基线列 + attributes（子字段 customer_code）+ relations（code/uuid） */
    private static SearchIndexDef desiredDefinition() {
        IndexFieldDef attributes = new IndexFieldDef("attributes", IndexFieldDef.TYPE_NESTED);
        attributes.setSubFields(List.of(new IndexFieldDef("customer_code", IndexFieldDef.TYPE_KEYWORD)));
        IndexFieldDef relations = new IndexFieldDef("relations", IndexFieldDef.TYPE_NESTED);
        relations.setArray(true);
        relations.setSubFields(List.of(
                new IndexFieldDef("code", IndexFieldDef.TYPE_KEYWORD),
                new IndexFieldDef("uuid", IndexFieldDef.TYPE_KEYWORD)));
        return new SearchIndexDef(INDEX, List.of(
                new IndexFieldDef("entity_code", IndexFieldDef.TYPE_KEYWORD),
                new IndexFieldDef("status", IndexFieldDef.TYPE_LONG),
                attributes,
                relations));
    }

    @Test
    @DisplayName("索引不存在：直接创建（含 Nested 子字段，类型映射正确）")
    void reconcile_indexAbsent_createsWithSubFields() {
        stubListIndexes();

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        ArgumentCaptor<CreateSearchIndexRequest> captor =
                ArgumentCaptor.forClass(CreateSearchIndexRequest.class);
        verify(client).createSearchIndex(captor.capture());
        verify(client, never()).describeSearchIndex(any(DescribeSearchIndexRequest.class));
        verify(client, never()).deleteSearchIndex(any(DeleteSearchIndexRequest.class));

        Map<String, FieldSchema> fields = captor.getValue().getIndexSchema().getFieldSchemas()
                .stream().collect(Collectors.toMap(FieldSchema::getFieldName, f -> f));
        assertThat(fields.get("attributes").getFieldType()).isEqualTo(FieldType.NESTED);
        assertThat(fields.get("attributes").getSubFieldSchemas())
                .extracting(FieldSchema::getFieldName).containsExactly("customer_code");
        assertThat(fields.get("attributes").getSubFieldSchemas().get(0).getFieldType())
                .isEqualTo(FieldType.KEYWORD);
        assertThat(fields.get("relations").getFieldType()).isEqualTo(FieldType.NESTED);
        assertThat(fields.get("relations").getSubFieldSchemas())
                .extracting(FieldSchema::getFieldName).containsExactly("code", "uuid");
        assertThat(fields.get("entity_code").getFieldType()).isEqualTo(FieldType.KEYWORD);
        assertThat(fields.get("status").getFieldType()).isEqualTo(FieldType.LONG);
    }

    @Test
    @DisplayName("索引存在且子字段已覆盖：跳过（不删除不重建）")
    void reconcile_indexCovered_skips() {
        stubListIndexes(INDEX);
        // 现有索引：基线列 + attributes 已含 customer_code（额外子字段不视为缺失）
        FieldSchema attributes = new FieldSchema("attributes", FieldType.NESTED);
        attributes.setSubFieldSchemas(List.of(
                new FieldSchema("customer_code", FieldType.KEYWORD),
                new FieldSchema("legacy_field", FieldType.KEYWORD)));
        FieldSchema relations = new FieldSchema("relations", FieldType.NESTED);
        relations.setSubFieldSchemas(List.of(
                new FieldSchema("code", FieldType.KEYWORD),
                new FieldSchema("uuid", FieldType.KEYWORD)));
        stubDescribe(new IndexSchema() {{
            setFieldSchemas(List.of(
                    new FieldSchema("entity_code", FieldType.KEYWORD),
                    new FieldSchema("status", FieldType.LONG),
                    attributes,
                    relations));
        }});

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        verify(client).describeSearchIndex(any(DescribeSearchIndexRequest.class));
        verify(client, never()).deleteSearchIndex(any(DeleteSearchIndexRequest.class));
        verify(client, never()).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    @Test
    @DisplayName("索引存在但 attributes 子字段缺失（真实缺陷现场）：删除后重建")
    void reconcile_missingSubFields_rebuilds() {
        stubListIndexes(INDEX);
        // 现有索引：attributes/relations 为 NESTED 但子字段数=0（bootstrap 基线状态）
        stubDescribe(new IndexSchema() {{
            setFieldSchemas(List.of(
                    new FieldSchema("entity_code", FieldType.KEYWORD),
                    new FieldSchema("status", FieldType.LONG),
                    new FieldSchema("attributes", FieldType.NESTED),
                    new FieldSchema("relations", FieldType.NESTED)));
        }});

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        verify(client).deleteSearchIndex(any(DeleteSearchIndexRequest.class));
        ArgumentCaptor<CreateSearchIndexRequest> captor =
                ArgumentCaptor.forClass(CreateSearchIndexRequest.class);
        verify(client).createSearchIndex(captor.capture());
        FieldSchema attributes = captor.getValue().getIndexSchema().getFieldSchemas().stream()
                .filter(f -> "attributes".equals(f.getFieldName())).findFirst().orElseThrow();
        assertThat(attributes.getSubFieldSchemas())
                .extracting(FieldSchema::getFieldName).containsExactly("customer_code");
    }

    @Test
    @DisplayName("索引存在但缺失顶层字段：同样触发重建")
    void reconcile_missingTopLevelField_rebuilds() {
        stubListIndexes(INDEX);
        stubDescribe(new IndexSchema() {{
            setFieldSchemas(List.of(new FieldSchema("entity_code", FieldType.KEYWORD)));
        }});

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        verify(client).deleteSearchIndex(any(DeleteSearchIndexRequest.class));
        verify(client).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    @Test
    @DisplayName("评审项 5：子字段名字命中但类型漂移（KEYWORD→LONG）视为未覆盖，触发重建")
    void reconcile_subFieldTypeDrift_rebuilds() {
        stubListIndexes(INDEX);
        // attributes.customer_code 类型漂移：LONG ≠ 期望 KEYWORD
        FieldSchema attributes = new FieldSchema("attributes", FieldType.NESTED);
        attributes.setSubFieldSchemas(List.of(
                new FieldSchema("customer_code", FieldType.LONG)));
        FieldSchema relations = new FieldSchema("relations", FieldType.NESTED);
        relations.setSubFieldSchemas(List.of(
                new FieldSchema("code", FieldType.KEYWORD),
                new FieldSchema("uuid", FieldType.KEYWORD)));
        stubDescribe(new IndexSchema() {{
            setFieldSchemas(List.of(
                    new FieldSchema("entity_code", FieldType.KEYWORD),
                    new FieldSchema("status", FieldType.LONG),
                    attributes,
                    relations));
        }});

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        verify(client).deleteSearchIndex(any(DeleteSearchIndexRequest.class));
        verify(client).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    // ==================== 评审项 2：delete 后重建退避重试 ====================

    @Test
    @DisplayName("delete 后 create 首败→退避重试成功（不抛异常）")
    void reconcile_recreate_retriesUntilSuccess() {
        stubListIndexes(INDEX);
        stubDescribeBaselineWithoutSubFields();
        TableStoreException transientFailure = mock(TableStoreException.class);
        when(transientFailure.getErrorCode()).thenReturn("OTSInternalServerError");
        when(transientFailure.getMessage()).thenReturn("injected transient failure");
        when(client.createSearchIndex(any(CreateSearchIndexRequest.class)))
                .thenThrow(transientFailure)
                .thenReturn(null);

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        verify(client).deleteSearchIndex(any(DeleteSearchIndexRequest.class));
        verify(client, times(2)).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    @Test
    @DisplayName("delete 后 create 重试耗尽：打 ERROR 并抛出（检索真空需人工介入）")
    void reconcile_recreate_exhausted_throws() {
        stubListIndexes(INDEX);
        stubDescribeBaselineWithoutSubFields();
        TableStoreException persistent = mock(TableStoreException.class);
        when(persistent.getErrorCode()).thenReturn("OTSInternalServerError");
        when(persistent.getMessage()).thenReturn("injected persistent failure");
        when(client.createSearchIndex(any(CreateSearchIndexRequest.class)))
                .thenThrow(persistent);

        assertThatThrownBy(() -> template.reconcileSearchIndex(TABLE, desiredDefinition()))
                .isInstanceOf(TablestoreAccessException.class);

        verify(client).deleteSearchIndex(any(DeleteSearchIndexRequest.class));
        // 首次 + 退避重试 3 次 = 4 次尝试
        verify(client, times(4)).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    @Test
    @DisplayName("delete 后 create 撞 OTSObjectAlreadyExist：幂等视为成功（单次尝试）")
    void reconcile_recreate_alreadyExist_treatedAsSuccess() {
        stubListIndexes(INDEX);
        stubDescribeBaselineWithoutSubFields();
        TableStoreException alreadyExist = mock(TableStoreException.class);
        when(alreadyExist.getErrorCode()).thenReturn("OTSObjectAlreadyExist");
        when(client.createSearchIndex(any(CreateSearchIndexRequest.class)))
                .thenThrow(alreadyExist);

        template.reconcileSearchIndex(TABLE, desiredDefinition());

        verify(client, times(1)).createSearchIndex(any(CreateSearchIndexRequest.class));
    }

    // ==================== 辅助 ====================

    /** 基线未覆盖 schema：attributes/relations 为 NESTED 但子字段数=0（bootstrap 基线状态） */
    private void stubDescribeBaselineWithoutSubFields() {
        stubDescribe(new IndexSchema() {{
            setFieldSchemas(List.of(
                    new FieldSchema("entity_code", FieldType.KEYWORD),
                    new FieldSchema("status", FieldType.LONG),
                    new FieldSchema("attributes", FieldType.NESTED),
                    new FieldSchema("relations", FieldType.NESTED)));
        }});
    }

    private void stubListIndexes(String... indexNames) {
        ListSearchIndexResponse listResponse = mock(ListSearchIndexResponse.class);
        List<SearchIndexInfo> infos = java.util.Arrays.stream(indexNames).map(name -> {
            SearchIndexInfo info = mock(SearchIndexInfo.class);
            when(info.getIndexName()).thenReturn(name);
            return info;
        }).collect(Collectors.toList());
        when(listResponse.getIndexInfos()).thenReturn(infos);
        when(client.listSearchIndex(any(ListSearchIndexRequest.class))).thenReturn(listResponse);
    }

    private void stubDescribe(IndexSchema schema) {
        DescribeSearchIndexResponse describeResponse = mock(DescribeSearchIndexResponse.class);
        when(describeResponse.getSchema()).thenReturn(schema);
        when(client.describeSearchIndex(any(DescribeSearchIndexRequest.class)))
                .thenReturn(describeResponse);
    }
}
