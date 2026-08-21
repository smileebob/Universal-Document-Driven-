package com.example.meta.service.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.cache.CacheProperties;
import com.example.meta.service.doc.DocSchemaService;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CacheWarmupRunner 启动预热测试。
 */
class CacheWarmupRunnerTest {

    @Test
    @DisplayName("开关开启：依次预热 schema 与 menu")
    void warmup_enabled() {
        CacheProperties properties = new CacheProperties();
        properties.setWarmupEnabled(true);
        SchemaService schemaService = mock(SchemaService.class);
        MenuService menuService = mock(MenuService.class);
        DocSchemaService docSchemaService = mock(DocSchemaService.class);
        new CacheWarmupRunner(properties, schemaService, menuService, docSchemaService).run(null);
        verify(schemaService).warmup();
        verify(docSchemaService).warmup();
        verify(menuService).warmup();
    }

    @Test
    @DisplayName("开关关闭：跳过预热")
    void warmup_disabled() {
        CacheProperties properties = new CacheProperties();
        properties.setWarmupEnabled(false);
        SchemaService schemaService = mock(SchemaService.class);
        MenuService menuService = mock(MenuService.class);
        DocSchemaService docSchemaService = mock(DocSchemaService.class);
        new CacheWarmupRunner(properties, schemaService, menuService, docSchemaService).run(null);
        verify(schemaService, never()).warmup();
        verify(docSchemaService, never()).warmup();
        verify(menuService, never()).warmup();
    }

    @Test
    @DisplayName("预热异常仅 WARN 不阻塞启动")
    void warmup_failure_noThrow() {
        CacheProperties properties = new CacheProperties();
        properties.setWarmupEnabled(true);
        SchemaService schemaService = mock(SchemaService.class);
        MenuService menuService = mock(MenuService.class);
        DocSchemaService docSchemaService = mock(DocSchemaService.class);
        doThrow(new RuntimeException("redis down")).when(schemaService).warmup();
        new CacheWarmupRunner(properties, schemaService, menuService, docSchemaService).run(null);
    }
}
