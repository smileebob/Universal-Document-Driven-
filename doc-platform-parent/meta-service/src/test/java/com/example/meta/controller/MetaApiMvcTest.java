package com.example.meta.controller;

import java.util.List;
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
import com.example.meta.api.dto.AttributeDTO;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.api.dto.AttributeType;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntityStatus;
import com.example.meta.service.doc.DocRuleConfigService;
import com.example.meta.service.doc.DocSchemaService;
import com.example.meta.service.doc.RuleLibService;
import com.example.meta.service.doc.StateMachineService;
import com.example.meta.service.schema.MenuService;
import com.example.meta.service.schema.SchemaService;
import com.example.meta.service.txconfig.TxConfigService;
import com.example.meta.web.dto.MenuMountedView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器 MVC 测试（MockMvc，覆盖主路径与 40006/40011/40401 口径）。
 */
@WebMvcTest({SchemaController.class, MenuController.class, InternalSchemaController.class})
@Import(GlobalExceptionHandler.class)
class MetaApiMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SchemaService schemaService;

    @MockitoBean
    private MenuService menuService;

    @MockitoBean
    private DocSchemaService docSchemaService;

    @MockitoBean
    private StateMachineService stateMachineService;

    @MockitoBean
    private DocRuleConfigService docRuleConfigService;

    @MockitoBean
    private RuleLibService ruleLibService;

    @MockitoBean
    private TxConfigService txConfigService;

    private static final String CREATE_BODY = """
            {"entity_code":"customer","entity_name":"客户","entity_type":"base",
             "attributes":[{"code":"attr_a","name":"属性A","data_type":"string"}]}""";

    private EntitySchema draft() {
        EntitySchema schema = new EntitySchema();
        schema.setCode("customer");
        schema.setName("客户");
        schema.setStatus(EntityStatus.DRAFT);
        schema.setVersion(0L);
        return schema;
    }

    private EntitySchemaDTO publishedDto() {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode("customer");
        dto.setName("客户");
        dto.setObjectType("entity");
        dto.setStatus(SchemaStatus.PUBLISHED);
        dto.setVersion(1L);
        AttributeDTO attr = new AttributeDTO();
        attr.setCode("attr_a");
        attr.setName("属性A");
        attr.setType(AttributeType.STRING);
        dto.setAttributes(List.of(attr));
        return dto;
    }

    @Test
    @DisplayName("POST /api/v1/schema/entity：创建成功响应 {entity_code,status:100,version:0}")
    void create_success() throws Exception {
        when(schemaService.create(any())).thenReturn(draft());
        mockMvc.perform(post("/api/v1/schema/entity")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.entity_code").value("customer"))
                .andExpect(jsonPath("$.data.status").value(100))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("评审修复 Major-3：全局 SNAKE_CASE，ApiResponse.traceId 输出为 trace_id")
    void snakeCase_traceIdField() throws Exception {
        when(schemaService.create(any())).thenReturn(draft());
        mockMvc.perform(post("/api/v1/schema/entity")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk())
                // trace_id 键存在（值为 null 亦证明命名策略生效），camelCase 键不得输出
                .andExpect(jsonPath("$.trace_id").value(nullValue()))
                .andExpect(jsonPath("$.data.create_time").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/schema/entity：必填缺失 → 40001/400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/v1/schema/entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity_name\":\"客户\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("POST /api/v1/schema/entity：编码重复 → 40006/400")
    void create_duplicate_40006() throws Exception {
        when(schemaService.create(any()))
                .thenThrow(new BizException(ErrorCode.CODE_DUPLICATE, "编码已存在: customer"));
        mockMvc.perform(post("/api/v1/schema/entity")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40006));
    }

    @Test
    @DisplayName("POST publish：校验失败 → 40011 + data.reasons")
    void publish_invalid_40011() throws Exception {
        when(schemaService.publish(anyString())).thenThrow(new BizException(
                ErrorCode.PUBLISH_INVALID, "发布校验未通过",
                Map.of("reasons", List.of("至少配置一个属性"))));
        mockMvc.perform(post("/api/v1/schema/entity/customer/publish"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40011))
                .andExpect(jsonPath("$.data.reasons[0]").value("至少配置一个属性"));
    }

    @Test
    @DisplayName("POST publish 成功：status=1、version+1")
    void publish_success() throws Exception {
        EntitySchema published = draft();
        published.setStatus(EntityStatus.PUBLISHED);
        published.setVersion(1L);
        published.setPublishedAt(100L);
        when(schemaService.publish("customer")).thenReturn(published);
        mockMvc.perform(post("/api/v1/schema/entity/customer/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/schema/entity/{code}：未命中 → 40401/404")
    void detail_notFound_40401() throws Exception {
        when(schemaService.detail("none")).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "实体定义不存在或未发布: none"));
        mockMvc.perform(get("/api/v1/schema/entity/none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("GET /api/v1/schema/entity：列表（prd/11 §一：data={total,items}）")
    void list_ok() throws Exception {
        when(schemaService.list(100, null)).thenReturn(List.of(draft()));
        mockMvc.perform(get("/api/v1/schema/entity").param("status", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].entity_code").value("customer"));
    }

    @Test
    @DisplayName("GET /api/v1/menu/mounted：扁平 items 视图携带 schema_version")
    void menuMounted_ok() throws Exception {
        MenuMountedView view = new MenuMountedView();
        view.setSchemaVersion(5L);
        MenuMountedView.MenuItem item = new MenuMountedView.MenuItem();
        item.setCode("customer");
        item.setMenuName("客户");
        item.setObjType("entity");
        item.setGroupCode("basic_data");
        item.setSortOrder(1L);
        view.setItems(List.of(item));
        when(menuService.mounted()).thenReturn(view);

        mockMvc.perform(get("/api/v1/menu/mounted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schema_version").value(5))
                .andExpect(jsonPath("$.data.items[0].code").value("customer"))
                .andExpect(jsonPath("$.data.items[0].menu_name").value("客户"))
                .andExpect(jsonPath("$.data.items[0].obj_type").value("entity"))
                .andExpect(jsonPath("$.data.items[0].group_code").value("basic_data"))
                .andExpect(jsonPath("$.data.items[0].sort_order").value(1));
    }

    @Test
    @DisplayName("PUT /api/v1/schema/{type}/{code}/menu：挂载配置成功")
    void menuMount_ok() throws Exception {
        mockMvc.perform(put("/api/v1/schema/entity/customer/menu")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menu_name\":\"客户\",\"group_code\":\"basic_data\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("internal：entity 契约接入 SchemaService；doc 系接入 service.doc（阶段二）")
    void internal_endpoints() throws Exception {
        when(schemaService.getPublishedDto("customer")).thenReturn(publishedDto());
        when(schemaService.listPublishedDtos()).thenReturn(List.of(publishedDto()));

        mockMvc.perform(get("/api/v1/internal/schema/entity/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("customer"))
                .andExpect(jsonPath("$.data.object_type").value("entity"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.attributes[0].type").value("STRING"));

        mockMvc.perform(get("/api/v1/internal/schema/entity/published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("customer"));

        // doc 系四端点阶段二已接入（缓存回源），mock 服务层返回
        DocSchemaDTO docDto = new DocSchemaDTO();
        docDto.setCode("sales_order");
        docDto.setName("外销订单");
        docDto.setStatus(1);
        docDto.setSchemaVersion(1);
        when(docSchemaService.getPublishedDto("sales_order")).thenReturn(docDto);
        when(docSchemaService.listPublishedDtos()).thenReturn(List.of(docDto));
        StateMachineDTO smDto = new StateMachineDTO();
        smDto.setDocCode("sales_order");
        smDto.setSchemaVersion(1L);
        when(stateMachineService.getPublishedDto("sales_order")).thenReturn(smDto);
        when(docRuleConfigService.getPublishedDto("sales_order"))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "规则绑定未配置: sales_order"));
        RuleLibDTO ruleDto = new RuleLibDTO();
        ruleDto.setUuid("rule-1");
        ruleDto.setRuleType("expression");
        ruleDto.setExpression("#amount > 0");
        ruleDto.setEnabled(true);
        when(ruleLibService.getPublishedDto("rule-1")).thenReturn(ruleDto);

        mockMvc.perform(get("/api/v1/internal/schema/doc/sales_order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("sales_order"))
                .andExpect(jsonPath("$.data.status").value(1));

        mockMvc.perform(get("/api/v1/internal/schema/doc/published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("sales_order"));

        mockMvc.perform(get("/api/v1/internal/schema/doc/sales_order/state-machine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.doc_code").value("sales_order"));

        // 服务层抛 NOT_FOUND → GlobalExceptionHandler 映射 HTTP 404 + code=40401
        mockMvc.perform(get("/api/v1/internal/schema/doc/sales_order/rules"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        mockMvc.perform(get("/api/v1/internal/schema/doc/sales_order/tx-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));

        mockMvc.perform(get("/api/v1/internal/schema/rule/rule-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("rule-1"))
                .andExpect(jsonPath("$.data.expression").value("#amount > 0"));
    }
}
