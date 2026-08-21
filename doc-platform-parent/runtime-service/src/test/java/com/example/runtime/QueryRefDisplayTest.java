package com.example.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.query.QueryDsl.FilterCondition;
import com.example.runtime.domain.query.QueryDsl.QueryRequest;
import com.example.runtime.domain.query.QueryDsl.QueryResult;
import com.example.runtime.service.QueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-09 ref 关联查询（{attribute_code}.ref.{display_field}）缺陷修复验证（任务 #11）。
 *
 * <p>两类用例：</p>
 * <ol>
 *   <li><b>恒假短路整改</b>：候选为空 / 实体匹配为空时直接返回空结果页
 *       （total=0、items 空、不构造任何查询），不再注入 term(uuid="")
 *       —— uuid 是主键而非 doc_data_index 索引字段，真实 OTS 会拒绝
 *       （OTSParameterInvalid field_name[uuid] is not existed，trace 5750bb85）；</li>
 *   <li><b>匹配链路正向复现</b>：任务 #8 已验证的数据形态
 *       （doc 行 relations=[{type,code,uuid,attribute_code}] + entity_data 主行
 *       attributes 含展示字段）→ 候选存在 → 匹配命中 → 结果含 ref_display，
 *       证明 pks 构造（PartitionKeys.dataKey=code_hash4(uuid)）/回读映射/匹配谓词无缺陷。</li>
 * </ol>
 */
class QueryRefDisplayTest {

    private InMemoryTablestore template;
    private QueryService queryService;

    private String customerUuidHit;
    private String customerUuidMiss;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        queryService = new QueryService(template,
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

        customerUuidHit = UUID.randomUUID().toString();
        customerUuidMiss = UUID.randomUUID().toString();
        // 实体主行（与 TablestoreEntityDataRepository.insert 同构）
        seedEntity("customer", customerUuidHit,
                "{\"customer_code\":\"C001\",\"customer_name\":\"Global Trading GmbH\"}");
        seedEntity("customer", customerUuidMiss,
                "{\"customer_code\":\"C002\",\"customer_name\":\"Ocean Import LLC\"}");
    }

    /** entity_data 主行：partition_key={code}_{hash4(uuid)} + uuid，status=1 有效 */
    private void seedEntity(String entityCode, String uuid, String attributesJson) {
        seedEntity(entityCode, uuid, attributesJson, 1L);
    }

    private void seedEntity(String entityCode, String uuid, String attributesJson, long status) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("entity_code", TsColumnValue.of(entityCode));
        columns.put("entity_type", TsColumnValue.of("base_data"));
        columns.put("status", TsColumnValue.of(status));
        columns.put("version", TsColumnValue.of(1L));
        columns.put("attributes", TsColumnValue.of(attributesJson));
        columns.put("relations", TsColumnValue.of("[]"));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        columns.put("update_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("entity_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(entityCode, uuid))
                .add("uuid", uuid)
                .build(), columns));
    }

    /** doc_data 主行（任务 #8 数据形态）：relations 五元组 JSON 列 + deleted 标记 */
    private String seedDoc(String orderNo, String refCustomerUuid, boolean deleted) {
        String uuid = UUID.randomUUID().toString();
        String relationsJson = refCustomerUuid == null ? "[]"
                : "[{\"type\":\"biz_entity\",\"code\":\"customer\",\"uuid\":\"" + refCustomerUuid
                        + "\",\"attribute_code\":\"customer\"}]";
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of("export_order"));
        columns.put("doc_type", TsColumnValue.of("business"));
        columns.put("current_status", TsColumnValue.of(100L));
        columns.put("version", TsColumnValue.of(1L));
        columns.put("attributes", TsColumnValue.of("{\"order_no\":\"" + orderNo + "\"}"));
        columns.put("relations", TsColumnValue.of(relationsJson));
        columns.put("deleted", TsColumnValue.of(deleted ? 1L : 0L));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        columns.put("update_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("export_order", uuid))
                .add("uuid", uuid)
                .build(), columns));
        return uuid;
    }

    private QueryRequest refRequest(String target, String field, String op, String value) {
        return new QueryRequest(target, List.of(
                new FilterCondition(field, op, value)), null, null, null);
    }

    @Test
    @DisplayName("匹配链路正向：候选存在→匹配命中→结果含 ref_display（eq）")
    void ref_match_hit_returns_items_with_ref_display() {
        String docHit = seedDoc("EO-2026-001", customerUuidHit, false);
        seedDoc("EO-2026-003", customerUuidMiss, false);

        QueryResult result = queryService.execute(refRequest("doc_data",
                "attributes.customer.ref.customer_name", "eq", "Global Trading GmbH"));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).get("uuid")).isEqualTo(docHit);
        Object refDisplay = result.items().get(0).get("ref_display");
        assertThat(refDisplay).isInstanceOf(Map.class);
        assertThat(String.valueOf(refDisplay)).contains("Global Trading");
    }

    @Test
    @DisplayName("匹配链路正向：contains 匹配命中且逻辑删单据不参与")
    void ref_contains_hit_excludes_deleted_doc() {
        String docHit = seedDoc("EO-2026-001", customerUuidHit, false);
        seedDoc("EO-2026-004", customerUuidHit, true);

        QueryResult result = queryService.execute(refRequest("doc_data",
                "attributes.customer.ref.customer_name", "contains", "Global"));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0).get("uuid")).isEqualTo(docHit);
        assertThat(result.items().get(0).get("ref_display")).isNotNull();
    }

    @Test
    @DisplayName("短路①：无候选（无 relations.attribute_code 命中）→ 空结果页 200，不构造查询")
    void ref_no_candidates_returns_empty_page() {
        // 仅有不含 customer 关系的单据
        seedDoc("EO-2026-009", null, false);

        QueryResult result = queryService.execute(refRequest("doc_data",
                "attributes.customer.ref.customer_name", "eq", "Global Trading GmbH"));

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(result.nextToken()).isNull();
    }

    @Test
    @DisplayName("短路②：候选存在但展示字段不匹配 → 空结果页 200，不构造查询")
    void ref_candidates_but_no_display_match_returns_empty_page() {
        seedDoc("EO-2026-003", customerUuidMiss, false);

        QueryResult result = queryService.execute(refRequest("doc_data",
                "attributes.customer.ref.customer_name", "eq", "Global Trading GmbH"));

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("短路②：候选实体已逻辑删（status=0）→ 空结果页 200")
    void ref_candidate_entity_deleted_returns_empty_page() {
        String deletedUuid = UUID.randomUUID().toString();
        seedEntity("currency", deletedUuid, "{\"currency_name\":\"美元\"}", 0L);
        // 单据引用该已删实体（attribute_code=currency）
        String uuid = UUID.randomUUID().toString();
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of("export_order"));
        columns.put("doc_type", TsColumnValue.of("business"));
        columns.put("current_status", TsColumnValue.of(100L));
        columns.put("attributes", TsColumnValue.of("{\"order_no\":\"EO-2026-006\"}"));
        columns.put("relations", TsColumnValue.of(
                "[{\"type\":\"biz_entity\",\"code\":\"currency\",\"uuid\":\"" + deletedUuid
                        + "\",\"attribute_code\":\"currency\"}]"));
        columns.put("deleted", TsColumnValue.of(0L));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        columns.put("update_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("export_order", uuid))
                .add("uuid", uuid)
                .build(), columns));

        QueryResult result = queryService.execute(refRequest("doc_data",
                "attributes.currency.ref.currency_name", "eq", "美元"));

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("entity_data target 不支持 ref 关联查询（40002 语义保持）")
    void ref_rejected_for_entity_target() {
        assertThatThrownBy(() -> queryService.execute(refRequest("entity_data",
                "attributes.level.ref.level_name", "eq", "战略客户")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 target=doc_data");
    }

    // ==================== 评审项 3：跨 Nested 元素幽灵行后置过滤 ====================

    @Test
    @DisplayName("后置过滤：跨 Nested 元素误命中幽灵行被剔除且 total 修正")
    void ref_ghost_row_cross_nested_elements_removed() {
        // 正常命中单据：同一 relations 元素同时满足 attribute_code=customer 且 uuid∈命中集
        String docHit = seedDoc("EO-2026-010", customerUuidHit, false);

        // 幽灵单据：元素 A uuid∈命中集但 attribute_code=supplier；
        // 元素 B attribute_code=customer 但 uuid∉命中集（C002 展示值不匹配）。
        // 两个独立 NestedQuery AND 均成立（跨元素误命中），但无单一元素同时满足
        String ghostUuid = UUID.randomUUID().toString();
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of("export_order"));
        columns.put("doc_type", TsColumnValue.of("business"));
        columns.put("current_status", TsColumnValue.of(100L));
        columns.put("version", TsColumnValue.of(1L));
        columns.put("attributes", TsColumnValue.of("{\"order_no\":\"EO-2026-011\"}"));
        columns.put("relations", TsColumnValue.of(
                "[{\"type\":\"biz_entity\",\"code\":\"supplier\",\"uuid\":\"" + customerUuidHit
                        + "\",\"attribute_code\":\"supplier\"},"
                        + "{\"type\":\"biz_entity\",\"code\":\"customer\",\"uuid\":\""
                        + customerUuidMiss + "\",\"attribute_code\":\"customer\"}]"));
        columns.put("deleted", TsColumnValue.of(0L));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        columns.put("update_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("export_order", ghostUuid))
                .add("uuid", ghostUuid)
                .build(), columns));

        QueryResult result = queryService.execute(refRequest("doc_data",
                "attributes.customer.ref.customer_name", "eq", "Global Trading GmbH"));

        // 幽灵行被后置过滤剔除，total 相应修正
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).get("uuid")).isEqualTo(docHit);
        assertThat(result.items().get(0).get("ref_display")).isNotNull();
    }

    // ==================== 评审项 4：filters 条数前置校验 ====================

    @Test
    @DisplayName("L5 前置校验：filters 超 32 条在 ref 候选扫描之前拒绝（40002）")
    void filter_count_exceeded_rejected_before_ref_resolution() {
        List<FilterCondition> filters = new ArrayList<>();
        filters.add(new FilterCondition("attributes.customer.ref.customer_name",
                "eq", "Global Trading GmbH"));
        for (int i = 0; i < 32; i++) {
            filters.add(new FilterCondition("doc_code", "eq", "export_order"));
        }
        QueryRequest request = new QueryRequest("doc_data", filters, null, null, null);

        assertThatThrownBy(() -> queryService.execute(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过滤条件数量超出限制");
    }
}
