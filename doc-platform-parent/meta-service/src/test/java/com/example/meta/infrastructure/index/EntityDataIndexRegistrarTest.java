package com.example.meta.infrastructure.index;

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
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntitySchemaRepository;
import com.example.meta.domain.schema.EntityStatus;
import com.example.meta.infrastructure.repository.MetaTableNames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EntityDataIndexRegistrar 动态子字段注册测试（含并集派生防收窄，任务 #10）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityDataIndexRegistrarTest {

    @Mock
    private TablestoreTemplate template;

    @Mock
    private EntitySchemaRepository schemaRepository;

    private EntityDataIndexRegistrar registrar() {
        when(schemaRepository.findAll()).thenReturn(List.of());
        return new EntityDataIndexRegistrar(template, schemaRepository);
    }

    private AttributeDef attr(String code, String dataType, boolean searchable) {
        AttributeDef def = new AttributeDef();
        def.setCode(code);
        def.setName(code);
        def.setDataType(dataType);
        def.setSearchable(searchable);
        return def;
    }

    private EntitySchema schema(EntityStatus status, List<AttributeDef> attributes) {
        EntitySchema schema = new EntitySchema();
        schema.setStatus(status);
        schema.setAttributes(attributes);
        return schema;
    }

    @Test
    @DisplayName("buildSubFields：仅 is_searchable=true 且类型可映射者入列")
    void buildSubFields_filter() {
        EntityDataIndexRegistrar registrar = registrar();
        List<IndexFieldDef> subFields = registrar.buildSubFields(List.of(
                attr("a", "string", true),
                attr("b", "table", true),
                attr("c", "integer", false),
                attr("d", "ref", true)));
        assertThat(subFields).hasSize(2);
        assertThat(registrar.buildSubFields(null)).isEmpty();
    }

    @Test
    @DisplayName("mapIndexType 全分支")
    void mapIndexType_branches() {
        assertThat(EntityDataIndexRegistrar.mapIndexType("string")).isEqualTo(IndexFieldDef.TYPE_KEYWORD);
        assertThat(EntityDataIndexRegistrar.mapIndexType("integer")).isEqualTo(IndexFieldDef.TYPE_LONG);
        assertThat(EntityDataIndexRegistrar.mapIndexType("decimal")).isEqualTo(IndexFieldDef.TYPE_DOUBLE);
        assertThat(EntityDataIndexRegistrar.mapIndexType("boolean")).isEqualTo(IndexFieldDef.TYPE_BOOLEAN);
        assertThat(EntityDataIndexRegistrar.mapIndexType("date")).isEqualTo(IndexFieldDef.TYPE_KEYWORD);
        assertThat(EntityDataIndexRegistrar.mapIndexType("datetime")).isEqualTo(IndexFieldDef.TYPE_KEYWORD);
        assertThat(EntityDataIndexRegistrar.mapIndexType("ref")).isEqualTo(IndexFieldDef.TYPE_KEYWORD);
        assertThat(EntityDataIndexRegistrar.mapIndexType("table")).isNull();
        assertThat(EntityDataIndexRegistrar.mapIndexType(null)).isNull();
    }

    @Test
    @DisplayName("registerQuietly：reconcileSearchIndex 调用（含基线列 + Nested）")
    void registerQuietly_invokesReconcileIndex() {
        EntityDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("a", "string", true)));
        verify(template).reconcileSearchIndex(eq(MetaTableNames.ENTITY_DATA), any(SearchIndexDef.class));
        verify(template, never()).ensureSearchIndex(any(), any());
    }

    @Test
    @DisplayName("派生索引定义：V1 基线全量列 + attributes 子字段 + relations 子字段（code/uuid/attribute_code）")
    void registerQuietly_derivedDefinition_baselineParity() {
        EntityDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("customer_code", "string", true)));

        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).reconcileSearchIndex(eq(MetaTableNames.ENTITY_DATA), captor.capture());
        SearchIndexDef indexDef = captor.getValue();
        assertThat(indexDef.getName()).isEqualTo(MetaTableNames.ENTITY_DATA_INDEX);
        // 基线固定列与 V1__base-tables.json 同构（重建路径不得丢失固定列字段）
        assertThat(indexDef.getFields()).extracting(IndexFieldDef::getName).containsExactly(
                "entity_code", "entity_type", "status", "creator", "create_dept",
                "create_time", "update_time", "attributes", "relations");

        IndexFieldDef attributes = indexDef.getFields().stream()
                .filter(f -> "attributes".equals(f.getName())).findFirst().orElseThrow();
        assertThat(attributes.getType()).isEqualTo(IndexFieldDef.TYPE_NESTED);
        assertThat(attributes.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactly("customer_code");
        assertThat(attributes.getSubFields().get(0).getType()).isEqualTo(IndexFieldDef.TYPE_KEYWORD);

        // relations 固定子字段 = runtime QueryService 实际引用全集
        // （relations[].code / relations[].uuid 关联反查 + relations[].attribute_code ref 关联查询）
        IndexFieldDef relations = indexDef.getFields().stream()
                .filter(f -> "relations".equals(f.getName())).findFirst().orElseThrow();
        assertThat(relations.getType()).isEqualTo(IndexFieldDef.TYPE_NESTED);
        assertThat(relations.isArray()).isTrue();
        assertThat(relations.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactly("code", "uuid", "attribute_code");
        assertThat(relations.getSubFields()).allSatisfy(sub ->
                assertThat(sub.getType()).isEqualTo(IndexFieldDef.TYPE_KEYWORD));
    }

    @Test
    @DisplayName("机制化派生：派生定义 ⊇ V1 基线全字段集（防再次收窄）")
    void derivedDefinition_coversV1Baseline() {
        EntityDataIndexRegistrar registrar = registrar();
        registrar.registerQuietly(List.of(attr("a", "string", true)));

        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).reconcileSearchIndex(eq(MetaTableNames.ENTITY_DATA), captor.capture());
        Map<String, IndexFieldDef> derived = captor.getValue().getFields().stream()
                .collect(Collectors.toMap(IndexFieldDef::getName, f -> f));

        List<IndexFieldDef> baseline = new TablestoreDefinitionLoader().loadAll().stream()
                .filter(table -> MetaTableNames.ENTITY_DATA.equals(table.getName()))
                .findFirst().orElseThrow()
                .getSearchIndexes().get(0).getFields();
        for (IndexFieldDef field : baseline) {
            assertThat(derived).containsKey(field.getName());
            assertThat(derived.get(field.getName()).getType()).isEqualTo(field.getType());
        }
    }

    @Test
    @DisplayName("并集派生：聚合全部已发布 schema 属性 + 显式并入当前发布 schema（防重建收窄）")
    void registerQuietly_unionOfPublishedSchemas() {
        // 已发布 schema A：order_no 可检索；已发布 schema B：amount 可检索；
        // 草稿 schema C：draft_only 不应入并集
        when(schemaRepository.findAll()).thenReturn(List.of(
                schema(EntityStatus.PUBLISHED, List.of(attr("order_no", "string", true))),
                schema(EntityStatus.PUBLISHED, List.of(attr("amount", "decimal", true))),
                schema(EntityStatus.DRAFT, List.of(attr("draft_only", "string", true)))));
        EntityDataIndexRegistrar registrar = new EntityDataIndexRegistrar(template, schemaRepository);

        // 当前发布 schema 仅含 customer_code（非超集）+ 与 A 同名的 order_no（当前覆盖）
        registrar.registerQuietly(List.of(
                attr("customer_code", "string", true),
                attr("order_no", "string", true)));

        ArgumentCaptor<SearchIndexDef> captor = ArgumentCaptor.forClass(SearchIndexDef.class);
        verify(template).reconcileSearchIndex(eq(MetaTableNames.ENTITY_DATA), captor.capture());
        IndexFieldDef attributes = captor.getValue().getFields().stream()
                .filter(f -> "attributes".equals(f.getName())).findFirst().orElseThrow();
        // 并集 = 已发布 A/B 属性 ∪ 当前发布属性（临时 schema 发布不再丢列收窄）
        assertThat(attributes.getSubFields()).extracting(IndexFieldDef::getName)
                .containsExactlyInAnyOrder("order_no", "amount", "customer_code");
        assertThat(attributes.getSubFields()).extracting(IndexFieldDef::getName)
                .doesNotContain("draft_only");
    }

    @Test
    @DisplayName("并集派生：同名属性以当前发布 schema 为准（类型覆盖）")
    void unionAttributes_currentSchemaWins() {
        when(schemaRepository.findAll()).thenReturn(List.of(
                schema(EntityStatus.PUBLISHED, List.of(attr("qty", "string", true)))));
        EntityDataIndexRegistrar registrar = new EntityDataIndexRegistrar(template, schemaRepository);

        List<AttributeDef> union = registrar.unionAttributes(List.of(attr("qty", "integer", true)));
        assertThat(union).hasSize(1);
        assertThat(union.get(0).getDataType()).isEqualTo("integer");
    }

    @Test
    @DisplayName("registerQuietly：无 searchable 属性跳过注册；异常不抛出")
    void registerQuietly_skipAndQuiet() {
        EntityDataIndexRegistrar registrar = registrar();
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
        when(schemaRepository.findAll()).thenThrow(new RuntimeException("ots down"));
        EntityDataIndexRegistrar registrar = new EntityDataIndexRegistrar(template, schemaRepository);
        registrar.registerQuietly(List.of(attr("a", "string", true)));
        verify(template, never()).reconcileSearchIndex(any(), any());
    }
}
