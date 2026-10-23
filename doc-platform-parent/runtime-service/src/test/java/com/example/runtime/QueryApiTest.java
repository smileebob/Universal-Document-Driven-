package com.example.runtime;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.model.ApiResponse;
import com.example.common.web.GlobalExceptionHandler;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.AttributeDTO;
import com.example.meta.api.dto.AttributeType;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.runtime.controller.QueryController;
import com.example.runtime.infrastructure.meta.MetaSchemaGateway;
import com.example.runtime.infrastructure.tablestore.TablestoreEntityDataRepository;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.port.AuditLogStore;
import com.example.runtime.infrastructure.tablestore.TablestoreAuditLogStore;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.DataService;
import com.example.runtime.service.QueryService;
import com.example.runtime.service.UniqueConstraintService;
import com.example.runtime.infrastructure.tablestore.TablestoreUniqueIndexStore;
import com.example.common.infrastructure.cache.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一查询 RD-10 验收（POST /api/v1/query）：
 * DSL 翻译 + 降级矩阵 L1~L7 全分支 + 逻辑删强制过滤 + token/offset 分页 +
 * count/sum/avg/group_by 聚合 + nested_eq。
 */
class QueryApiTest {

    private ObjectMapper mapper;
    private DataService dataService;
    private MockMvc mockMvc;

    private String dept1;
    private String dept2;
    private String customerAuuid;
    private String customerBuuid;
    private String deletedCustomerUuid;
    private InMemoryTablestore template;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        MetaSchemaClient metaSchemaClient = mock(MetaSchemaClient.class);
        when(metaSchemaClient.getEntitySchema("customer")).thenReturn(ApiResponse.ok(customerSchema()));
        when(metaSchemaClient.getEntitySchema("department")).thenReturn(ApiResponse.ok(departmentSchema()));

        MetaSchemaGateway gateway = new MetaSchemaGateway(metaSchemaClient, mock(CacheService.class), mapper);
        TablestoreEntityDataRepository dataRepository = new TablestoreEntityDataRepository(template, mapper);
        UniqueConstraintService uniqueConstraintService =
                new UniqueConstraintService(new TablestoreUniqueIndexStore(template));
        AuditLogStore noopAuditStore = new AuditLogStore() {
            @Override
            public void append(AuditEntry entry) {
                // no-op
            }

            @Override
            public SearchResult search(QuerySpec spec) {
                return new TablestoreAuditLogStore(template).search(spec);
            }
        };
        AuditService auditService = new AuditService(noopAuditStore, Runnable::run);
        dataService = new DataService(gateway, dataRepository, dataRepository,
                uniqueConstraintService, auditService, mapper);
        QueryService queryService = new QueryService(template, mapper);

        mockMvc = MockMvcBuilders.standaloneSetup(new QueryController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();

        seedData();
    }

    @Test
    @DisplayName("doc_data 查询：日志行不可见，内部列（record_kind/inflight/last_event_seq）不出参")
    void docQuery_excludesLogRowsAndInternalColumns() throws Exception {
        String uuid = "doc-business-1";
        String partitionKey = PartitionKeys.dataKey("probe_doc", uuid);
        Map<String, TsColumnValue> business = new java.util.LinkedHashMap<>();
        business.put("doc_code", TsColumnValue.of("probe_doc"));
        business.put("current_status", TsColumnValue.of(100L));
        business.put("deleted", TsColumnValue.of(0L));
        business.put("attributes", TsColumnValue.of("{}"));
        business.put("relations", TsColumnValue.of("[]"));
        business.put("record_kind", TsColumnValue.of(0L));
        business.put("last_event_seq", TsColumnValue.of(1L));
        business.put("inflight_operation_id", TsColumnValue.of(""));
        business.put("inflight_saga_id", TsColumnValue.of(""));
        business.put("inflight_lease_until", TsColumnValue.of(0L));
        business.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", partitionKey).add("uuid", uuid).build(), business));
        Map<String, TsColumnValue> logRow = new java.util.LinkedHashMap<>();
        logRow.put("doc_code", TsColumnValue.of("probe_doc"));
        logRow.put("current_status", TsColumnValue.of(100L));
        logRow.put("deleted", TsColumnValue.of(1L));
        logRow.put("record_kind", TsColumnValue.of(1L));
        logRow.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("uuid", "op:" + uuid + ":op-1").build(), logRow));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"doc_data\",\"page\":{\"limit\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].uuid").value(uuid))
                .andExpect(jsonPath("$.data.items[0].record_kind").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].last_event_seq").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].inflight_operation_id").doesNotExist());
    }

    /** 种子数据：2 部门 + 5 客户（其中 1 个逻辑删），level 分布 1/2/3/3/5 */
    private void seedData() {
        dept1 = (String) dataService.create("department", Map.of("name", "研发部"),
                "u1", "d1", null, null).get("uuid");
        dept2 = (String) dataService.create("department", Map.of("name", "市场部"),
                "u1", "d1", null, null).get("uuid");
        customerAuuid = createCustomer("客户A", "Q601", 1, dept1);
        customerBuuid = createCustomer("客户B", "Q602", 2, dept1);
        createCustomer("客户C", "Q603", 3, dept2);
        createCustomer("客户D", "Q604", 3, dept2);
        deletedCustomerUuid = createCustomer("客户E", "Q605", 5, null);
        dataService.delete("customer", deletedCustomerUuid, "u1", null, null);
    }

    private String createCustomer(String name, String code, int level, String deptUuid) {
        java.util.LinkedHashMap<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("name", name);
        attributes.put("code", code);
        attributes.put("level", level);
        if (deptUuid != null) {
            attributes.put("dept", deptUuid);
        }
        return (String) dataService.create("customer", attributes, "u1", "d1", null, null).get("uuid");
    }

    // ==================== 基础过滤与投影 ====================

    @Test
    @DisplayName("target 非法 / 空 body → 40002")
    void invalid_target_40002() throws Exception {
        mockMvc.perform(query("{\"target\":\"foo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("列表查询：逻辑删强制过滤（4 条活跃客户）+ 行投影")
    void list_entity_data_with_logical_delete_filter() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].uuid").exists())
                .andExpect(jsonPath("$.data.items[0].entity_code").value("customer"))
                .andExpect(jsonPath("$.data.items[0].attributes.code").exists());
    }

    @Test
    @DisplayName("attributes.{code} eq 动态属性过滤")
    void filter_attributes_eq() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"attributes.code\",\"op\":\"eq\",\"value\":\"Q601\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].attributes.name").value("客户A"));
    }

    @Test
    @DisplayName("attributes.{code} contains 通配（design/09 §4.2）：命中 / 超长拒绝 / 非法操作符拒绝")
    void filter_attributes_contains() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"attributes.name\",\"op\":\"contains\",\"value\":\"客户A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].attributes.code").value("Q601"));

        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"attributes.code\",\"op\":\"contains\",\"value\":\"Q6\"}]}"))
                .andExpect(status().isOk())
                // Q601~Q604 命中；已逻辑删的 Q605 不得命中
                .andExpect(jsonPath("$.data.total").value(4));

        // 超长（>30 字符）→ 40002
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"attributes.name\",\"op\":\"contains\",\"value\":\""
                        + "a".repeat(31) + "\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        // attributes.* 不支持的操作符 → 40002
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"attributes.code\",\"op\":\"prefix\",\"value\":\"Q\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("顶层数值字段 range + in：status in [1] 命中 4；create_time gte 命中全部")
    void filter_status_in_and_range() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":["
                        + "{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"},"
                        + "{\"field\":\"status\",\"op\":\"in\",\"value\":[1]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));

        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":["
                        + "{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"},"
                        + "{\"field\":\"create_time\",\"op\":\"gte\",\"value\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));
    }

    @Test
    @DisplayName("nested_eq：relations[].code / relations[].uuid 反查")
    void filter_nested_eq_relations() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"relations[].code\",\"op\":\"nested_eq\",\"value\":\"department\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));

        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"relations[].uuid\",\"op\":\"nested_eq\",\"value\":\"" + dept1 + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("uuid 主键列过滤：eq 命中单条 / in 命中多条（仿真层主键列检索回归）")
    void filter_uuid_eq_and_in() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"uuid\",\"op\":\"eq\",\"value\":\"" + customerAuuid + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].uuid").value(customerAuuid))
                .andExpect(jsonPath("$.data.items[0].attributes.name").value("客户A"));

        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"uuid\",\"op\":\"in\",\"value\":[\""
                        + customerAuuid + "\",\"" + customerBuuid + "\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // 已逻辑删数据的 uuid 不得命中
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"uuid\",\"op\":\"eq\",\"value\":\"" + deletedCustomerUuid + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("contains / prefix 字符串操作符")
    void filter_contains_and_prefix() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":["
                        + "{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"},"
                        + "{\"field\":\"creator\",\"op\":\"contains\",\"value\":\"u\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));

        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":["
                        + "{\"field\":\"entity_code\",\"op\":\"prefix\",\"value\":\"cust\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));
    }

    @Test
    @DisplayName("doc_data：流转后单据保持可见（deleted 列活跃过滤，非 current_status）")
    void doc_data_visible_after_transition() throws Exception {
        seedDocRow("uuid-doc-draft", 100L, 0L);
        seedDocRow("uuid-doc-submitted", 10L, 0L);
        seedDocRow("uuid-doc-approved", 20L, 0L);
        seedDocRow("uuid-doc-deleted", 100L, 1L);

        mockMvc.perform(query("{\"target\":\"doc_data\","
                        + "\"filters\":[{\"field\":\"doc_code\",\"op\":\"eq\",\"value\":\"export_order\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                // 流转至 10/20 的单据必须可见；逻辑删（deleted=1）不可见
                .andExpect(jsonPath("$.data.items[?(@.uuid=='uuid-doc-submitted')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.uuid=='uuid-doc-approved')]").exists());
    }

    /** 直写 doc_data 行（可控 current_status / deleted，模拟流转后行形态） */
    private void seedDocRow(String uuid, long status, long deleted) {
        java.util.Map<String, TsColumnValue> columns = new java.util.LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of("export_order"));
        columns.put("current_status", TsColumnValue.of(status));
        columns.put("version", TsColumnValue.of(1L));
        columns.put("attributes", TsColumnValue.of("{}"));
        columns.put("relations", TsColumnValue.of("[]"));
        columns.put("deleted", TsColumnValue.of(deleted));
        columns.put("creator", TsColumnValue.of("alice"));
        columns.put("create_time", TsColumnValue.of(1L));
        columns.put("update_time", TsColumnValue.of(1L));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("export_order", uuid))
                .add("uuid", uuid)
                .build(), columns));
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("token 分页：limit=2 两页翻完 4 条，末页 next_token 为 null")
    void token_pagination() throws Exception {
        String page1 = mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"}],"
                        + "\"page\":{\"limit\":2}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.next_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String token = com.jayway.jsonpath.JsonPath.read(page1, "$.data.next_token");

        String page2 = mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"}],"
                        + "\"page\":{\"limit\":2,\"token\":\"" + token + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Object token2 = com.jayway.jsonpath.JsonPath.read(page2, "$.data.next_token");
        org.assertj.core.api.Assertions.assertThat(token2).isNull();
    }

    @Test
    @DisplayName("offset 分页：正常返回且响应不回 next_token")
    void offset_mode_no_next_token() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"}],"
                        + "\"page\":{\"offset\":1,\"limit\":2}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.next_token").doesNotExist());
    }

    // ==================== 降级矩阵 L1~L7 ====================

    @Test
    @DisplayName("L1 limit 越界 / L4 深翻超限 / offset+token 互斥 → 40002")
    void paging_degradation_rejections() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\",\"page\":{\"limit\":101}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{\"target\":\"entity_data\",\"page\":{\"limit\":0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{\"target\":\"entity_data\",\"page\":{\"offset\":2000,\"limit\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{\"target\":\"entity_data\",\"page\":{\"offset\":1,\"limit\":1,\"token\":\"abc\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("L2 in 词数 >1024 / L3 contains >30 字符 → 40002")
    void in_terms_and_contains_degradation() throws Exception {
        StringBuilder inValues = new StringBuilder("[");
        for (int i = 0; i < 1025; i++) {
            inValues.append(i == 0 ? "" : ",").append(i);
        }
        inValues.append("]");
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"status\",\"op\":\"in\",\"value\":" + inValues + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        String tooLong = "a".repeat(33);
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"creator\",\"op\":\"contains\",\"value\":\"" + tooLong + "\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("L5 filters >32 / L6 聚合 >5 / 聚合重名 / 非法类型 → 40002")
    void filters_and_aggregations_degradation() throws Exception {
        StringBuilder filters = new StringBuilder("[");
        for (int i = 0; i < 33; i++) {
            filters.append(i == 0 ? "" : ",")
                    .append("{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"}");
        }
        filters.append("]");
        mockMvc.perform(query("{\"target\":\"entity_data\",\"filters\":" + filters + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        StringBuilder aggs = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            aggs.append(i == 0 ? "" : ",")
                    .append("{\"name\":\"a").append(i).append("\",\"type\":\"count\"}");
        }
        aggs.append("]");
        mockMvc.perform(query("{\"target\":\"entity_data\",\"aggregations\":" + aggs + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        mockMvc.perform(query("{\"target\":\"entity_data\",\"aggregations\":["
                        + "{\"name\":\"x\",\"type\":\"count\"},{\"name\":\"x\",\"type\":\"count\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        mockMvc.perform(query("{\"target\":\"entity_data\",\"aggregations\":["
                        + "{\"name\":\"x\",\"type\":\"median\",\"field\":\"status\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("字段白名单 / 非法操作符 / attributes 非法操作符 / 排序字段限制 → 40002")
    void field_and_operator_rejections() throws Exception {
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"hacker_field\",\"op\":\"eq\",\"value\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"entity_code\",\"op\":\"like\",\"value\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"filters\":[{\"field\":\"attributes.code\",\"op\":\"in\",\"value\":[\"x\"]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mockMvc.perform(query("{\"target\":\"entity_data\","
                        + "\"sorts\":[{\"field\":\"hacker_field\",\"order\":\"asc\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    // ==================== 聚合 ====================

    @Test
    @DisplayName("聚合：count / sum(attributes.level) / avg / group_by Top 桶")
    void aggregations() throws Exception {
        String filterCustomer = "\"filters\":[{\"field\":\"entity_code\",\"op\":\"eq\",\"value\":\"customer\"}]";

        mockMvc.perform(query("{\"target\":\"entity_data\"," + filterCustomer + ","
                        + "\"aggregations\":[{\"name\":\"cnt\",\"type\":\"count\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aggregations.cnt[0].count").value(4));

        mockMvc.perform(query("{\"target\":\"entity_data\"," + filterCustomer + ","
                        + "\"aggregations\":["
                        + "{\"name\":\"total_level\",\"type\":\"sum\",\"field\":\"attributes.level\"},"
                        + "{\"name\":\"avg_level\",\"type\":\"avg\",\"field\":\"attributes.level\"}]}"))
                .andExpect(status().isOk())
                // 1 + 2 + 3 + 3 = 9（已删的 level=5 不计入）
                .andExpect(jsonPath("$.data.aggregations.total_level[0].value").value(9.0))
                .andExpect(jsonPath("$.data.aggregations.avg_level[0].value").value(2.25));

        mockMvc.perform(query("{\"target\":\"entity_data\"," + filterCustomer + ","
                        + "\"aggregations\":["
                        + "{\"name\":\"by_level\",\"type\":\"group_by\",\"field\":\"attributes.level\"}]}"))
                .andExpect(status().isOk())
                // 桶按 count desc：level=3 两条居首
                .andExpect(jsonPath("$.data.aggregations.by_level[0].key").value("3"))
                .andExpect(jsonPath("$.data.aggregations.by_level[0].count").value(2))
                .andExpect(jsonPath("$.data.aggregations.by_level.length()").value(3));
    }

    // ==================== 测试夹具 ====================

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query(String body) {
        return post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private EntitySchemaDTO customerSchema() {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode("customer");
        dto.setName("客户");
        dto.setStatus(SchemaStatus.PUBLISHED);
        dto.setVersion(1L);
        dto.setPublishedAt(System.currentTimeMillis());
        dto.setAttributes(List.of(
                attr("name", "名称", AttributeType.STRING, true, false, null, 1),
                attr("code", "编码", AttributeType.STRING, true, true, null, 2),
                attr("level", "等级", AttributeType.NUMBER, false, false, null, 3),
                attr("dept", "所属部门", AttributeType.REF, false, false, "department", 4)));
        return dto;
    }

    private EntitySchemaDTO departmentSchema() {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode("department");
        dto.setName("部门");
        dto.setStatus(SchemaStatus.PUBLISHED);
        dto.setVersion(1L);
        dto.setPublishedAt(System.currentTimeMillis());
        dto.setAttributes(List.of(attr("name", "名称", AttributeType.STRING, true, false, null, 1)));
        return dto;
    }

    private AttributeDTO attr(String code, String name, AttributeType type, boolean required,
                              boolean unique, String refEntityCode, int sortOrder) {
        AttributeDTO dto = new AttributeDTO();
        dto.setCode(code);
        dto.setName(name);
        dto.setType(type);
        dto.setRequired(required);
        dto.setUnique(unique);
        dto.setRefEntityCode(refEntityCode);
        dto.setSortOrder(sortOrder);
        return dto;
    }
}
