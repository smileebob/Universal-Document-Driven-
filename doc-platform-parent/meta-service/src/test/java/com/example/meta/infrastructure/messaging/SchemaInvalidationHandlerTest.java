package com.example.meta.infrastructure.messaging;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SchemaInvalidationHandler 消费处理器测试（BC-01~06 契约）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaInvalidationHandlerTest {

    @Mock
    private CacheService cacheService;

    private SchemaInvalidationHandler handler() {
        return new SchemaInvalidationHandler(cacheService, new ObjectMapper());
    }

    private static String message(String domain, String code, long version) {
        return "{\"event_id\":\"e1\",\"event_type\":\"SCHEMA_PUBLISHED\",\"timestamp\":1,"
                + "\"payload\":{\"cache_domain\":\"" + domain + "\",\"code\":\"" + code
                + "\",\"version\":" + version + "}}";
    }

    @Test
    @DisplayName("合法消息：DEL 对应缓存键（BC-01 幂等）")
    void handle_validMessage_evictsKey() {
        handler().handle(message("entity_schema", "customer", 5));
        verify(cacheService).evictLocalOnly(CacheKeyBuilder.entitySchema("customer"));
    }

    @Test
    @DisplayName("BC-05 乱序过滤：旧 version 消息跳过失效")
    void handle_outOfOrder_skipped() {
        SchemaInvalidationHandler handler = handler();
        handler.handle(message("entity_schema", "customer", 5));
        handler.handle(message("entity_schema", "customer", 3));
        verify(cacheService, times(1)).evictLocalOnly(CacheKeyBuilder.entitySchema("customer"));
    }

    @Test
    @DisplayName("menu_config 域映射到全局菜单树键")
    void handle_menuDomain_menuTreeKey() {
        handler().handle(message("menu_config", "entity_customer", 2));
        verify(cacheService).evictLocalOnly(CacheKeyBuilder.menuTree());
    }

    @Test
    @DisplayName("缺少 cache_domain 的消息丢弃，不触发失效")
    void handle_missingDomain_dropped() {
        handler().handle("{\"event_id\":\"e1\",\"payload\":{\"code\":\"x\",\"version\":1}}");
        verify(cacheService, never()).evictLocalOnly(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("未知缓存域忽略，不触发失效")
    void handle_unknownDomain_ignored() {
        handler().handle(message("unknown_domain", "x", 1));
        verify(cacheService, never()).evictLocalOnly(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("BC-04：非法 JSON 仅 WARN，不抛异常")
    void handle_invalidJson_noThrow() {
        handler().handle("{invalid-json");
        verify(cacheService, never()).evictLocalOnly(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("messageBody 结构：event_id/event_type/timestamp/payload 四段")
    void messageBody_structure() {
        Map<String, Object> body = SchemaInvalidationHandler.messageBody(
                "evt-1", "SCHEMA_PUBLISHED", 123L, "entity_schema", "customer", 9L);
        assertThat(body.get("event_id")).isEqualTo("evt-1");
        assertThat(body.get("event_type")).isEqualTo("SCHEMA_PUBLISHED");
        assertThat(body.get("timestamp")).isEqualTo(123L);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        assertThat(payload.get("cache_domain")).isEqualTo("entity_schema");
        assertThat(payload.get("code")).isEqualTo("customer");
        assertThat(payload.get("version")).isEqualTo(9L);
    }

    @Test
    @DisplayName("toCacheKey 七域映射与未知域 null")
    void toCacheKey_mapping() {
        assertThat(SchemaInvalidationHandler.toCacheKey("entity_schema", "c"))
                .isEqualTo(CacheKeyBuilder.entitySchema("c"));
        assertThat(SchemaInvalidationHandler.toCacheKey("doc_schema", "c"))
                .isEqualTo(CacheKeyBuilder.docSchema("c"));
        assertThat(SchemaInvalidationHandler.toCacheKey("state_machine", "c"))
                .isEqualTo(CacheKeyBuilder.stateMachine("c"));
        assertThat(SchemaInvalidationHandler.toCacheKey("rule_lib", "u"))
                .isEqualTo(CacheKeyBuilder.rule("u"));
        assertThat(SchemaInvalidationHandler.toCacheKey("doc_rule_config", "c"))
                .isEqualTo(CacheKeyBuilder.docRuleConfig("c"));
        assertThat(SchemaInvalidationHandler.toCacheKey("tx_config", "c"))
                .isEqualTo(CacheKeyBuilder.txConfig("c"));
        assertThat(SchemaInvalidationHandler.toCacheKey("menu_config", "k"))
                .isEqualTo(CacheKeyBuilder.menuTree());
        assertThat(SchemaInvalidationHandler.toCacheKey("other", "c")).isNull();
    }
}
