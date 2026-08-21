package com.example.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.common.web.GlobalExceptionHandler;
import com.example.runtime.controller.QueryController;
import com.example.runtime.service.QueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RD-09/11 关联反查验收（步骤 38）：统一查询对 doc_data 的
 * relations 嵌套反查——"引用了某客户的所有外销订单"。
 *
 * <p>DSL 形态（前端 RelationPanel 消费）：
 * {@code {"target":"doc_data","filters":[{"field":"relations[].uuid","op":"nested_eq","value":"<客户uuid>"}]}}；
 * 多值反查用 {@code nested_in}（relations[].uuid in [...]）。</p>
 */
class DocRelationQueryApiTest {

    private InMemoryTablestore template;
    private MockMvc mockMvc;

    private String customerUuid;
    private String otherCustomerUuid;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        QueryService queryService = new QueryService(template,
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
        mockMvc = MockMvcBuilders.standaloneSetup(new QueryController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().setPropertyNamingStrategy(
                                PropertyNamingStrategies.SNAKE_CASE)))
                .build();

        customerUuid = UUID.randomUUID().toString();
        otherCustomerUuid = UUID.randomUUID().toString();
        // 两单引用同一客户，一单引用其他客户，一单已逻辑删（也引用该客户）
        seedDoc("export_order", "EO-001", customerUuid, false);
        seedDoc("export_order", "EO-002", customerUuid, false);
        seedDoc("export_order", "EO-003", otherCustomerUuid, false);
        seedDoc("export_order", "EO-004", customerUuid, true);
    }

    /** 直接落 doc_data 行（relations JSON 嵌套列，deleted 逻辑删标记） */
    private void seedDoc(String docCode, String orderNo, String refCustomerUuid, boolean deleted) {
        String uuid = UUID.randomUUID().toString();
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("type", "biz_entity");
        relation.put("code", "customer");
        relation.put("uuid", refCustomerUuid);
        relation.put("attribute_code", "customer");
        String relationsJson = "[" + toJson(relation) + "]";

        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of(docCode));
        columns.put("doc_type", TsColumnValue.of(docCode));
        columns.put("current_status", TsColumnValue.of(100L));
        columns.put("version", TsColumnValue.of(1L));
        columns.put("attributes", TsColumnValue.of("{\"order_no\":\"" + orderNo + "\"}"));
        columns.put("relations", TsColumnValue.of(relationsJson));
        columns.put("deleted", TsColumnValue.of(deleted ? 1L : 0L));
        columns.put("creator", TsColumnValue.of("u1"));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        columns.put("update_time", TsColumnValue.of(System.currentTimeMillis()));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(docCode, uuid))
                .add("uuid", uuid)
                .build(), columns));
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query(String body) {
        return post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("nested_eq relations[].uuid：引用了某客户的所有单据（逻辑删强制过滤）")
    void doc_data_nested_eq_relations_uuid() throws Exception {
        mockMvc.perform(query("{\"target\":\"doc_data\",\"filters\":["
                        + "{\"field\":\"doc_code\",\"op\":\"eq\",\"value\":\"export_order\"},"
                        + "{\"field\":\"relations[].uuid\",\"op\":\"nested_eq\",\"value\":\""
                        + customerUuid + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // EO-001/EO-002 命中；EO-004 已逻辑删不得命中
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("nested_eq relations[].code：按被引用实体类型反查")
    void doc_data_nested_eq_relations_code() throws Exception {
        mockMvc.perform(query("{\"target\":\"doc_data\",\"filters\":["
                        + "{\"field\":\"relations[].code\",\"op\":\"nested_eq\",\"value\":\"customer\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    @DisplayName("nested_in relations[].uuid：多客户批量反查（RelationPanel 多值场景）")
    void doc_data_nested_in_relations_uuid() throws Exception {
        mockMvc.perform(query("{\"target\":\"doc_data\",\"filters\":["
                        + "{\"field\":\"relations[].uuid\",\"op\":\"nested_in\",\"value\":[\""
                        + customerUuid + "\",\"" + otherCustomerUuid + "\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));
    }
}
