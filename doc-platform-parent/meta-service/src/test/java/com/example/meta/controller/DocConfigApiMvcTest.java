package com.example.meta.controller;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.web.GlobalExceptionHandler;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.doc.StateMachineConfig;
import com.example.meta.service.doc.DocRuleConfigService;
import com.example.meta.service.doc.DocSchemaService;
import com.example.meta.service.doc.RuleLibService;
import com.example.meta.service.doc.StateMachineService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单据配置域控制器 MVC 测试（DC/SM/RE，任务书 t8）。
 *
 * <p>覆盖：DC-06 编码重复 40006 / 发布校验失败 40011+reasons；状态机预校验
 * 硬错误 40011+reasons 与未配置 40401；规则在线测试主路径与沙箱拒绝分支；
 * 规则删除被绑定 40010+bound_docs；绑定引用缺失 40004+missing_rules。</p>
 */
@WebMvcTest({DocSchemaController.class, StateMachineController.class,
        RuleController.class, DocRuleConfigController.class})
@Import(GlobalExceptionHandler.class)
class DocConfigApiMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocSchemaService docSchemaService;

    @MockitoBean
    private StateMachineService stateMachineService;

    @MockitoBean
    private RuleLibService ruleLibService;

    @MockitoBean
    private DocRuleConfigService docRuleConfigService;

    private static final String CREATE_BODY = """
            {"doc_code":"sales_order","doc_name":"外销订单","doc_type":"sales",
             "attributes":[{"code":"amount","name":"金额","data_type":"decimal","required":true}],
             "status_defs":[{"status":100,"name":"暂存","editable":true,"terminal":false},
                            {"status":10,"name":"已提交","editable":false,"terminal":false},
                            {"status":99,"name":"已关闭","editable":false,"terminal":true}]}""";

    private DocSchemaDefinition draft(long status, long version) {
        DocSchemaDefinition definition = mock(DocSchemaDefinition.class);
        when(definition.getCode()).thenReturn("sales_order");
        when(definition.getName()).thenReturn("外销订单");
        when(definition.getStatus()).thenReturn(status);
        when(definition.getVersion()).thenReturn(version);
        return definition;
    }

    // ==================== DC-01~06 ====================

    @Test
    @DisplayName("DC-01 创建单据定义：响应 {doc_code,status:100,version:0}")
    void docCreate_success() throws Exception {
        DocSchemaDefinition created = draft(100L, 0L);
        when(docSchemaService.create(any())).thenReturn(created);
        mockMvc.perform(post("/api/v1/schema/doc")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.doc_code").value("sales_order"))
                .andExpect(jsonPath("$.data.status").value(100))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("DC-06 编码重复 → 40006/400")
    void docCreate_duplicate_40006() throws Exception {
        when(docSchemaService.create(any()))
                .thenThrow(new BizException(ErrorCode.CODE_DUPLICATE, "编码已存在: sales_order"));
        mockMvc.perform(post("/api/v1/schema/doc")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40006));
    }

    @Test
    @DisplayName("DC-01 必填缺失（doc_code）→ 40001/400")
    void docCreate_validation_40001() throws Exception {
        mockMvc.perform(post("/api/v1/schema/doc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doc_name\":\"外销订单\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("DC-03 发布成功：status=1、version+1")
    void docPublish_success() throws Exception {
        DocSchemaDefinition published = draft(1L, 1L);
        when(docSchemaService.publish("sales_order")).thenReturn(published);
        mockMvc.perform(post("/api/v1/schema/doc/sales_order/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("DC-06 发布校验失败 → 40011 + data.reasons（leader 补项）")
    void docPublish_invalid_40011() throws Exception {
        when(docSchemaService.publish(anyString())).thenThrow(new BizException(
                ErrorCode.PUBLISH_INVALID, "发布校验未通过",
                Map.of("reasons", List.of("status_defs 缺少终态 99", "属性 amount 缺少 name"))));
        mockMvc.perform(post("/api/v1/schema/doc/sales_order/publish"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40011))
                .andExpect(jsonPath("$.data.reasons[0]").value("status_defs 缺少终态 99"))
                .andExpect(jsonPath("$.data.reasons[1]").value("属性 amount 缺少 name"));
    }

    @Test
    @DisplayName("DC-02 详情未命中 → 40401/404")
    void docDetail_notFound_40401() throws Exception {
        when(docSchemaService.detail("none")).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "单据定义不存在: none"));
        mockMvc.perform(get("/api/v1/schema/doc/none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("DC-02 非草稿修改 → 40007")
    void docUpdate_notEditable_40007() throws Exception {
        when(docSchemaService.update(anyString(), any())).thenThrow(
                new BizException(ErrorCode.NOT_EDITABLE, "已发布定义不可修改"));
        mockMvc.perform(put("/api/v1/schema/doc/sales_order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doc_name\":\"外销订单V2\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40007));
    }

    @Test
    @DisplayName("DC-02 详情序列化：attributes 输出展示字段全集（visible/editable/is_list_column/is_searchable/unique）")
    void docDetail_attributesSerializedFullFields() throws Exception {
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setUuid("uuid-export");
        definition.setCode("export_order");
        definition.setName("外销订单");
        definition.setDocType("sales");
        definition.setStatus(1L);
        definition.setVersion(2L);
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("code", "total_amount");
        attr.put("name", "订单总额");
        attr.put("data_type", "decimal");
        attr.put("required", true);
        attr.put("visible", true);
        attr.put("editable", true);
        attr.put("is_list_column", true);
        attr.put("is_searchable", false);
        attr.put("unique", false);
        definition.setAttributes(List.of(attr));
        definition.setStatusDefs(List.of(Map.of("status", 100, "name", "暂存")));
        when(docSchemaService.detail("export_order")).thenReturn(definition);

        mockMvc.perform(get("/api/v1/schema/doc/export_order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes[0].required").value(true))
                .andExpect(jsonPath("$.data.attributes[0].visible").value(true))
                .andExpect(jsonPath("$.data.attributes[0].editable").value(true))
                .andExpect(jsonPath("$.data.attributes[0].is_list_column").value(true))
                .andExpect(jsonPath("$.data.attributes[0].is_searchable").value(false))
                .andExpect(jsonPath("$.data.attributes[0].unique").value(false));
    }

    // ==================== SM-01/02 ====================

    @Test
    @DisplayName("SM-01 保存状态机成功：返回 doc_code/version")
    void stateMachine_save_ok() throws Exception {
        when(stateMachineService.save(anyString(), any())).thenReturn(
                Map.of("doc_code", "sales_order", "version", 1, "warnings", List.of()));
        mockMvc.perform(put("/api/v1/schema/doc/sales_order/state-machine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"states\":[{\"status\":100,\"name\":\"暂存\"}],\"transitions\":[{\"start\":100,\"end\":[10]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.doc_code").value("sales_order"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("SM-01 预校验硬错误（终态出边/99 起始）→ 40011+reasons")
    void stateMachine_save_invalid_40011() throws Exception {
        when(stateMachineService.save(anyString(), any())).thenThrow(new BizException(
                ErrorCode.PUBLISH_INVALID, "状态机预校验未通过",
                Map.of("reasons", List.of("终态 99 存在出边", "起始状态不唯一"))));
        mockMvc.perform(put("/api/v1/schema/doc/sales_order/state-machine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"states\":[{\"status\":100,\"name\":\"暂存\"}],\"transitions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40011))
                .andExpect(jsonPath("$.data.reasons[0]").value("终态 99 存在出边"));
    }

    @Test
    @DisplayName("SM-02 查询未配置 → 40401/404")
    void stateMachine_get_notFound() throws Exception {
        when(stateMachineService.get("none")).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "状态机未配置: none"));
        mockMvc.perform(get("/api/v1/schema/doc/none/state-machine"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== RE-01/02/05 ====================

    @Test
    @DisplayName("RE-01 创建规则：响应 {rule_uuid,version:0}")
    void ruleCreate_success() throws Exception {
        RuleLibEntry entry = mock(RuleLibEntry.class);
        when(entry.getUuid()).thenReturn("rule-1");
        when(entry.getVersion()).thenReturn(0L);
        when(ruleLibService.create(any())).thenReturn(entry);
        mockMvc.perform(post("/api/v1/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rule_name\":\"金额非负\",\"rule_type\":\"expression\",\"expression\":\"#amount > 0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rule_uuid").value("rule-1"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("RE-02 在线测试主路径：passed=true（与运行时同一执行器）")
    void ruleTest_passed() throws Exception {
        when(ruleLibService.test(anyString(), any())).thenReturn(Map.of(
                "rule_uuid", "rule-1", "passed", true, "skipped", false, "reason", ""));
        mockMvc.perform(post("/api/v1/rule/rule-1/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":{\"amount\":100}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rule_uuid").value("rule-1"))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.skipped").value(false));
    }

    @Test
    @DisplayName("RE-02 在线测试沙箱拒绝：T()/构造器越权 → passed=false + 求值异常原因")
    void ruleTest_sandboxRejected() throws Exception {
        when(ruleLibService.test(anyString(), any())).thenReturn(Map.of(
                "rule_uuid", "rule-evil", "passed", false, "skipped", false,
                "reason", "表达式求值异常"));
        mockMvc.perform(post("/api/v1/rule/rule-evil/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.reason").value("表达式求值异常"));
    }

    @Test
    @DisplayName("RE-02 删除被绑定规则 → 40010 + bound_docs")
    void ruleDelete_bound_40010() throws Exception {
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.REF_PROTECTED,
                        "规则被单据绑定", Map.of("bound_docs", List.of("sales_order"))))
                .when(ruleLibService).delete("rule-1");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/rule/rule-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010))
                .andExpect(jsonPath("$.data.bound_docs[0]").value("sales_order"));
    }

    @Test
    @DisplayName("RE-05 保存绑定引用缺失规则 → 40004 + missing_rules")
    void ruleConfig_missingRules_40004() throws Exception {
        when(docRuleConfigService.save(anyString(), any())).thenThrow(new BizException(
                ErrorCode.REF_INVALID, "引用规则不存在",
                Map.of("missing_rules", List.of("rule-x"))));
        mockMvc.perform(put("/api/v1/schema/doc/sales_order/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"create_rules\":[\"rule-x\"],\"status_rules\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004))
                .andExpect(jsonPath("$.data.missing_rules[0]").value("rule-x"));
    }

    @Test
    @DisplayName("RE-05 查询绑定未配置 → 40401/404")
    void ruleConfig_get_notFound() throws Exception {
        DocRuleConfig config = null;
        when(docRuleConfigService.get("none")).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "规则绑定未配置: none"));
        mockMvc.perform(get("/api/v1/schema/doc/none/rules"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }
}
