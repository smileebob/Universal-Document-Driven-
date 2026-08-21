package com.example.meta.service.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.cache.CacheProperties;
import com.example.meta.service.doc.DocSchemaService;

/**
 * 启动预热执行器（design/06 §5.1：应用就绪后全量已发布定义 + 菜单视图写入两级缓存）。
 *
 * <p>预热失败仅 WARN 不阻塞启动（Redis 不可达时依赖懒加载 + TTL 兜底）。</p>
 */
@Component
public class CacheWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupRunner.class);

    private final CacheProperties cacheProperties;
    private final SchemaService schemaService;
    private final MenuService menuService;
    private final DocSchemaService docSchemaService;

    public CacheWarmupRunner(CacheProperties cacheProperties,
                             SchemaService schemaService,
                             MenuService menuService,
                             DocSchemaService docSchemaService) {
        this.cacheProperties = cacheProperties;
        this.schemaService = schemaService;
        this.menuService = menuService;
        this.docSchemaService = docSchemaService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!cacheProperties.isWarmupEnabled()) {
            log.info("[cache-warmup] 预热开关关闭，跳过");
            return;
        }
        try {
            schemaService.warmup();
            docSchemaService.warmup();
            menuService.warmup();
        } catch (RuntimeException e) {
            log.warn("[cache-warmup] 缓存预热失败（不阻塞启动，依赖懒加载兜底）: {}", e.getMessage());
        }
    }
}
