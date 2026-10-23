package com.example.runtime.infrastructure.meta;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.model.ApiResponse;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * runtime 启动预热（评审补项）：拉取已发布实体/单据定义全量写入两级缓存，
 * 消除重启后冷启动窗口的回源尖峰（BC-03 配套：重启丢广播窗口由 TTL + 预热兜底）。
 *
 * <p>容错：meta-service 未就绪或拉取失败仅 WARN，不阻塞启动（运行时按需回源）。</p>
 */
@Component
public class RuntimeSchemaWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSchemaWarmupRunner.class);

    private final MetaSchemaClient metaSchemaClient;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public RuntimeSchemaWarmupRunner(MetaSchemaClient metaSchemaClient,
                                     CacheService cacheService,
                                     ObjectMapper objectMapper) {
        this.metaSchemaClient = metaSchemaClient;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        warmupEntities();
        warmupDocs();
    }

    /** 已发布实体定义预热 */
    private void warmupEntities() {
        try {
            ApiResponse<List<EntitySchemaDTO>> response = metaSchemaClient.listPublishedEntitySchemas();
            if (response == null || response.getCode() != ApiResponse.CODE_SUCCESS
                    || response.getData() == null) {
                log.warn("[warmup] 实体定义预热跳过（meta 未就绪或返回错误码），运行时按需回源");
                return;
            }
            int count = 0;
            for (EntitySchemaDTO dto : response.getData()) {
                if (dto.getCode() == null) {
                    continue;
                }
                cacheService.putLocal(CacheKeyBuilder.entitySchema(dto.getCode()), toJson(dto));
                count++;
            }
            log.info("[warmup] 实体定义预热完成: count={}", count);
        } catch (RuntimeException e) {
            log.warn("[warmup] 实体定义预热失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    /** 已发布单据定义预热 */
    private void warmupDocs() {
        try {
            ApiResponse<List<DocSchemaDTO>> response = metaSchemaClient.listPublishedDocSchemas();
            if (response == null || response.getCode() != ApiResponse.CODE_SUCCESS
                    || response.getData() == null) {
                log.warn("[warmup] 单据定义预热跳过（meta 未就绪或返回错误码），运行时按需回源");
                return;
            }
            int count = 0;
            for (DocSchemaDTO dto : response.getData()) {
                if (dto.getCode() == null) {
                    continue;
                }
                cacheService.putLocal(CacheKeyBuilder.docSchema(dto.getCode()), toJson(dto));
                count++;
            }
            log.info("[warmup] 单据定义预热完成: count={}", count);
        } catch (RuntimeException e) {
            log.warn("[warmup] 单据定义预热失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("预热缓存值序列化失败", e);
        }
    }
}
