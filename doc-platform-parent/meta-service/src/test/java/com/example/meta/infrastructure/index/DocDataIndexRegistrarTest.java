package com.example.meta.infrastructure.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreDefinitionLoader;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.infrastructure.repository.MetaTableNames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocDataIndexRegistrar 动态子字段注册测试
 * （doc_data_index：attributes/relations/related_forms；含并集派生防收窄，任务 #10）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocDataIndexRegistrarTest {

    @Mock
    private TablestoreTemplate template;

    @Mock
    private DocSchemaDefinition.Repository docSchemaRepository;

    private DocDataIndexRegistrar registrar() {
        when(docSchemaRepository.findAll()).thenReturn(List.of());
        return new DocDataIndexRegistrar(template, docSchemaRepository);
    }

    private static Map<String, Object> attr(String code, String dataType, boolean searchable) {
        Map<String, Object> attr = new HashMap<>();
        attr.put("code", code);
        attr.put("name", code);
        attr.put("data_type", dataType);
        attr.put("is_searchable", searchable);
        return attr;
    }

    private static DocSchemaDefinition definition(long status, List<Map<String, Object>> attributes) {
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setStatus(status);
        definition.setAttributes(attributes);
        return definition;
    }

    @Test
    @DisplayName("buildSubFields：仅 is_searchable=true 且类型可映射者入列；null 安全")
    void buildSubFields_filter() {
        DocDataIndexRegistrar registrar = registrar();
        List<IndexFieldDef> subFields = registrar.buildSubFields(List.of(
                attr("order_no", "string", true),
                attr("lines", "table", true),
                attr("amount", "decimal", false),
                attr("customer", "ref", true)));
        assertThat(subFields).extracting(IndexFieldDef::getName)
                .containsExactly("order_no", "customer");
        assertThat(registrar.buildSubFields(null)).isEmpty();
    }

    @Test
    @DisplayName("registerQuietly：reconcileSearchIndex 调用（doc_data 目标表）")
    void registerQuietly_invokesReconcileIndex() {
        DocDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("order_no", "string", true)));
        verify(template).reconcileSearchIndex(eq(MetaTableNames.DOC_DATA), any(SearchIndexDef.class));
        verify(template, never()).ensureSearchIndex(any(), any());
    }

    @Test
    @DisplayName("派生索引定义：V1 基线全量列 + attributes 子字段 + relations/related_forms 固定子字段")
    void registerQuietly_derivedDefinition_baselineParity() {
        DocDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("order_no", "string", true)));

        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).reconcileSearchIndex(eq(MetaTableNames.DOC_DATA), captor.capture());
        SearchIndexDef indexDef = captor.getValue();
        assertThat(indexDef.getName()).isEqualTo(MetaTableNames.DOC_DATA_INDEX);
        // 基线固定列与 V1__base-tables.json 同构（重建路径不得丢失固定列字段）
        assertThat(indexDef.getFields()).extracting(IndexFieldDef::getName).containsExactly(
                "doc_code", "doc_type", "current_status", "creator", "create_dept",
                "create_time", "update_time", "deleted", "attributes", "relations", "related_forms");

        IndexFieldDef attributes = indexDef.getFields().stream()
                .filter(f -> "attributes".equals(f.getName())).findFirst().orElseThrow();
        assertThat(attributes.getType()).isEqualTo(IndexFieldDef.TYPE_NESTED);
        assertThat(attributes.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactly("order_no");

        // relations 固定子字段 = runtime QueryService 实际引用全集
        // （relations[].code / relations[].uuid 关联反查 + relations[].attribute_code ref 关联查询）
        IndexFieldDef relations = indexDef.getFields().stream()
                .filter(f -> "relations".equals(f.getName())).findFirst().orElseThrow();
        assertThat(relations.isArray()).isTrue();
        assertThat(relations.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactly("code", "uuid", "attribute_code");

        IndexFieldDef relatedForms = indexDef.getFields().stream()
                .filter(f -> "related_forms".equals(f.getName())).findFirst().orElseThrow();
        assertThat(relatedForms.isArray()).isTrue();
        assertThat(relatedForms.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactly("form_type", "code", "uuid");
    }

    @Test
    @DisplayName("机制化派生：派生定义 ⊇ V1 基线全字段集（含 deleted，防再次收窄）")
    void derivedDefinition_coversV1Baseline() {
        DocDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("order_no", "string", true)));

        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).reconcileSearchIndex(eq(MetaTableNames.DOC_DATA), captor.capture());
        Map<String, IndexFieldDef> derived = captor.getValue().getFields().stream()
                .collect(Collectors.toMap(IndexFieldDef::getName, f -> f));

        List<IndexFieldDef> baseline = new TablestoreDefinitionLoader().loadAll().stream()
                .filter(table -> MetaTableNames.DOC_DATA.equals(table.getName()))
                .findFirst().orElseThrow()
                .getSearchIndexes().get(0).getFields();
        assertThat(derived).containsKey("deleted");
        assertThat(derived.get("deleted").getType()).isEqualTo(IndexFieldDef.TYPE_LONG);
        for (IndexFieldDef field : baseline) {
            assertThat(derived).containsKey(field.getName());
            assertThat(derived.get(field.getName()).getType()).isEqualTo(field.getType());
        }
    }

    @Test
    @DisplayName("并集派生：聚合全部已发布 schema 属性 + 显式并入当前发布 schema（防重建收窄）")
    void registerQuietly_unionOfPublishedSchemas() {
        // 已发布 schema A：order_no 可检索；已发布 schema B：delivery_date 可检索；
        // 草稿 schema C：draft_only 不应入并集
        when(docSchemaRepository.findAll()).thenReturn(List.of(
                definition(DocSchemaDefinition.STATUS_PUBLISHED,
                        List.of(attr("order_no", "string", true))),
                definition(DocSchemaDefinition.STATUS_PUBLISHED,
                        List.of(attr("delivery_date", "date", true))),
                definition(DocSchemaDefinition.STATUS_DRAFT,
                        List.of(attr("draft_only", "string", true)))));
        DocDataIndexRegistrar registrar = new DocDataIndexRegistrar(template, docSchemaRepository);

        // 当前发布 schema 仅含 amount（非超集）+ 与 A 同名的 order_no（当前覆盖）
        registrar.registerQuietly(List.of(
                attr("amount", "decimal", true),
                attr("order_no", "string", true)));

        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).reconcileSearchIndex(eq(MetaTableNames.DOC_DATA), captor.capture());
        IndexFieldDef attributes = captor.getValue().getFields().stream()
                .filter(f -> "attributes".equals(f.getName())).findFirst().orElseThrow();
        // 并集 = 已发布 A/B 属性 ∪ 当前发布属性（临时 schema 发布不再丢列收窄）
        assertThat(attributes.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactlyInAnyOrder("order_no", "delivery_date", "amount");
        assertThat(attributes.getSubFields()).extracting(IndexFieldDef::getName)
                .doesNotContain("draft_only");
    }

    @Test
    @DisplayName("并集派生：同名属性以当前发布 schema 为准（覆盖）")
    void unionAttributes_currentSchemaWins() {
        when(docSchemaRepository.findAll()).thenReturn(List.of(
                definition(DocSchemaDefinition.STATUS_PUBLISHED,
                        List.of(attr("qty", "string", false)))));
        DocDataIndexRegistrar registrar = new DocDataIndexRegistrar(template, docSchemaRepository);

        List<Map<String, Object>> union = registrar.unionAttributes(
                List.of(attr("qty", "integer", true)));
        assertThat(union).hasSize(1);
        assertThat(union.get(0).get("data_type")).isEqualTo("integer");
        assertThat(union.get(0).get("is_searchable")).isEqualTo(true);
    }

    @Test
    @DisplayName("registerQuietly：无 searchable 属性跳过注册；异常不抛出")
    void registerQuietly_skipAndQuiet() {
        DocDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("b", "string", false)));
        verify(template, never()).reconcileSearchIndex(any(), any());

        doThrow(new RuntimeException("ots down")).when(template)
                .reconcileSearchIndex(any(), any());
        registrar.registerQuietly(List.of(attr("a", "string", true)));
        // 不抛异常即为通过
    }

    @Test
    @DisplayName("registerQuietly：仓储查询异常仅 WARN 不抛出（不阻断发布）")
    void registerQuietly_repositoryFailureQuiet() {
        when(docSchemaRepository.findAll()).thenThrow(new RuntimeException("ots down"));
        DocDataIndexRegistrar registrar = new DocDataIndexRegistrar(template, docSchemaRepository);
        registrar.registerQuietly(List.of(attr("a", "string", true)));
        verify(template, never()).reconcileSearchIndex(any(), any());
    }
}
