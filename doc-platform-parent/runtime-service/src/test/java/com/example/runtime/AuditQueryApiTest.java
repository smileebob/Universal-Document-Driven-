package com.example.runtime;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.web.GlobalExceptionHandler;
import com.example.runtime.controller.AuditQueryController;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.infrastructure.tablestore.TablestoreAuditLogStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计查询接口验收（步骤 39 RD-12：GET /api/v1/audit，走 audit_log_index）。
 *
 * <p>覆盖：target 维度筛选、操作类型/操作人筛选、时间范围、nextToken 分页、
 * before_value/after_value 快照回显（前端 JSON Diff 契约）。</p>
 */
class AuditQueryApiTest {

    private InMemoryTablestore template;
    private TablestoreAuditLogStore store;
    private MockMvc mockMvc;

    private String docUuid;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        store = new TablestoreAuditLogStore(template);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditQueryController(store))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().setPropertyNamingStrategy(
                                PropertyNamingStrategies.SNAKE_CASE)))
                .build();

        docUuid = UUID.randomUUID().toString();
        // 配置变更审计（含 before/after 快照）
        store.append(new AuditEntry(UUID.randomUUID().toString(), "config",
                "doc_schema", "export_order", null, "admin", 1_000L,
                "{\"version\":1}", "{\"version\":2}", null, null));
        // 数据创建审计（before 为 null）
        store.append(new AuditEntry(UUID.randomUUID().toString(), "create",
                "doc", "export_order", docUuid, "u1", 2_000L,
                null, "{\"current_status\":100}", null, null));
        // 流转审计（before/after 双快照）
        store.append(new AuditEntry(UUID.randomUUID().toString(), "transition",
                "doc", "export_order", docUuid, "u1", 3_000L,
                "{\"current_status\":100}", "{\"current_status\":10}", null, null));
        // 其他目标的干扰行
        store.append(new AuditEntry(UUID.randomUUID().toString(), "update",
                "entity", "customer", UUID.randomUUID().toString(), "u2", 4_000L,
                "{\"a\":1}", "{\"a\":2}", null, null));
    }

    @Test
    @DisplayName("按 target_code 筛选：命中 3 条，含 before/after 快照")
    void filter_by_target_code() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("target_code", "export_order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                // operate_time 倒序：流转（3000）在最前，双快照回显
                .andExpect(jsonPath("$.data.items[0].operation").value("transition"))
                .andExpect(jsonPath("$.data.items[0].before_value").value("{\"current_status\":100}"))
                .andExpect(jsonPath("$.data.items[0].after_value").value("{\"current_status\":10}"));
    }

    @Test
    @DisplayName("按 target_uuid + operation 复合筛选")
    void filter_by_uuid_and_operation() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("target_uuid", docUuid)
                        .param("operation", "create"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].after_value").value("{\"current_status\":100}"));
    }

    @Test
    @DisplayName("按操作人 + target_type 筛选")
    void filter_by_operator_and_type() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("operator", "u2")
                        .param("target_type", "entity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].operation").value("update"));
    }

    @Test
    @DisplayName("时间范围筛选（闭区间）")
    void filter_by_time_range() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("start_time", "2000")
                        .param("end_time", "3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("分页：limit=2 返回 next_token，续翻取剩余")
    void pagination_with_next_token() throws Exception {
        String body = mockMvc.perform(get("/api/v1/audit").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.next_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.data.next_token");
        mockMvc.perform(get("/api/v1/audit").param("limit", "2").param("next_token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("limit 越界 → 40002")
    void limit_out_of_range_40002() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }
}
