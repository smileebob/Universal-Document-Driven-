package com.example.runtime.infrastructure.meta;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.model.ApiResponse;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.AttributeDTO;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.runtime.domain.model.DocRuleBindings;
import com.example.runtime.domain.model.DocStateMachine;
import com.example.runtime.domain.model.EntitySchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import feign.FeignException;
import feign.Request;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetaSchemaGateway 防腐网关测试（评审修复 Major-7：fallback 误映射 404；
 * 阶段二补项：meta 未配置配置项返回 HTTP 404 + body{code:40401} 的容错映射）。
 *
 * <p>分流口径：远端业务 40401（HTTP 200 响应体或 Feign HTTP 404 body 两种形态）
 * 一律映射未命中（null → 空流转表/空绑定 / 定义缺失抛 NOT_FOUND）；
 * Feign fallback 的 50000 与其余错误码一律抛 SYSTEM_ERROR「依赖服务暂不可用」。</p>
 */
@ExtendWith(MockitoExtension.class)
class MetaSchemaGatewayTest {

    @Mock
    private MetaSchemaClient metaSchemaClient;

    private MetaSchemaGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new MetaSchemaGateway(metaSchemaClient, mock(CacheService.class),
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    /** 构造带 request 的 Feign 错误响应（Response.builder 强制要求 original request） */
    private static Response mockResponse(int status, String bodyJson) {
        Request request = Request.create(Request.HttpMethod.GET, "http://meta-service/api/v1/internal/schema",
                Map.<String, java.util.Collection<String>>of(), new byte[0], StandardCharsets.UTF_8);
        Response.Builder builder = Response.builder()
                .status(status).reason("mock").request(request)
                .headers(Map.<String, java.util.Collection<String>>of("Content-Type", List.of("application/json")));
        if (bodyJson != null) {
            builder.body(bodyJson.getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    @Test
    @DisplayName("fallback 返回 50000：抛 SYSTEM_ERROR 而非 NOT_FOUND")
    void fallback_systemError_notMisMappedTo404() {
        when(metaSchemaClient.getEntitySchema("customer"))
                .thenReturn(ApiResponse.fail(50000, "meta-service 不可用：connect timed out"));
        assertThatThrownBy(() -> gateway.getEntitySchema("customer"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(50000);
    }

    @Test
    @DisplayName("远端明确业务 404（40401）：抛 NOT_FOUND")
    void remote_explicit404_mapsNotFound() {
        when(metaSchemaClient.getEntitySchema("none"))
                .thenReturn(ApiResponse.fail(40401, "实体定义不存在或未发布: none"));
        assertThatThrownBy(() -> gateway.getEntitySchema("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(40401);
    }

    @Test
    @DisplayName("其余非成功错误码（如 42901）：一律 SYSTEM_ERROR")
    void otherErrorCode_systemError() {
        when(metaSchemaClient.getEntitySchema("busy"))
                .thenReturn(ApiResponse.fail(42901, "请求处理中"));
        assertThatThrownBy(() -> gateway.getEntitySchema("busy"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(50000);
    }

    @Test
    @DisplayName("null 响应：SYSTEM_ERROR（依赖异常兜底）")
    void nullResponse_systemError() {
        when(metaSchemaClient.getEntitySchema("null")).thenReturn(null);
        assertThatThrownBy(() -> gateway.getEntitySchema("null"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(50000);
    }

    @Test
    @DisplayName("成功响应：已发布定义映射为运行域模型")
    void success_mapsDomainSchema() {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode("customer");
        dto.setName("客户");
        dto.setStatus(SchemaStatus.PUBLISHED);
        AttributeDTO attr = new AttributeDTO();
        attr.setCode("customer_name");
        attr.setName("客户名称");
        attr.setType(com.example.meta.api.dto.AttributeType.STRING);
        attr.setRequired(true);
        attr.setSortOrder(1);
        dto.setAttributes(List.of(attr));
        when(metaSchemaClient.getEntitySchema("customer")).thenReturn(ApiResponse.ok(dto));

        EntitySchema schema = gateway.getEntitySchema("customer");
        assertThat(schema.getCode()).isEqualTo("customer");
        assertThat(schema.getStatus()).isEqualTo(EntitySchema.STATUS_ACTIVE);
        assertThat(schema.getAttributes()).hasSize(1);
        assertThat(schema.getAttributes().get(0).getCode()).isEqualTo("customer_name");
    }

    @Test
    @DisplayName("成功但状态非 PUBLISHED：抛 NOT_FOUND（禁止写入）")
    void success_notPublished_notFound() {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode("draft");
        dto.setName("草稿");
        dto.setStatus(SchemaStatus.DRAFT);
        when(metaSchemaClient.getEntitySchema("draft")).thenReturn(ApiResponse.ok(dto));
        assertThatThrownBy(() -> gateway.getEntitySchema("draft"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(40401);
    }

    @Test
    @DisplayName("Feign HTTP 404 + body code=40401：未配置=未命中，状态机返回空流转表")
    void feign404_body40401_stateMachine_emptyMiss() {
        // 异常须在 when() 之外构造，避免 Mockito UnfinishedStubbing 连锁污染
        FeignException ex = FeignException.errorStatus("test",
                mockResponse(404, "{\"code\":40401,\"message\":\"状态机配置不存在\"}"));
        when(metaSchemaClient.getStateMachine("po")).thenThrow(ex);

        DocStateMachine sm = gateway.getStateMachine("po");
        assertThat(sm.getDocCode()).isEqualTo("po");
        assertThat(sm.getTransitions()).isEmpty();
    }

    @Test
    @DisplayName("Feign HTTP 404 + body code=40401：未配置=未命中，规则绑定返回空绑定")
    void feign404_body40401_ruleBindings_emptyMiss() {
        FeignException ex = FeignException.errorStatus("test",
                mockResponse(404, "{\"code\":40401,\"message\":\"规则绑定不存在\"}"));
        when(metaSchemaClient.getDocRuleConfig("po")).thenThrow(ex);

        DocRuleBindings bindings = gateway.getDocRuleBindings("po");
        assertThat(bindings.getCreateRules()).isEmpty();
        assertThat(bindings.getStatusRules()).isEmpty();
    }

    @Test
    @DisplayName("Feign HTTP 500：SYSTEM_ERROR（依赖服务不可用，不得误判未命中）")
    void feign500_systemError() {
        FeignException ex = FeignException.errorStatus("test", mockResponse(500, null));
        when(metaSchemaClient.getStateMachine("po")).thenThrow(ex);
        assertThatThrownBy(() -> gateway.getStateMachine("po"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(50000);
    }
}
