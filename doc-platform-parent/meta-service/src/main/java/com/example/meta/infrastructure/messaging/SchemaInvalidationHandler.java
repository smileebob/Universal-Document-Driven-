package com.example.meta.infrastructure.messaging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.constants.CacheDomains;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 失效广播消费处理器（纯逻辑类，脱离 RocketMQ SDK 可单测）。
 *
 * <p>BC 契约落点：</p>
 * <ul>
 *   <li>BC-01 幂等：DEL 幂等，重复消息无副作用；</li>
 *   <li>BC-02 自包含：仅凭消息体 payload 完成失效，不回查存储；</li>
 *   <li>BC-04 失败放弃：解析/处理异常仅 WARN，不重试不级联（≤3 次由 Broker
 *       重试语义承担，本处理器不抛回消费线程以避免无限重投）；</li>
 *   <li>BC-05 乱序过滤：version 水位线，旧版本消息跳过；</li>
 *   <li>BC-06 不级联写：只删缓存键，不触发任何存储写。</li>
 * </ul>
 */
@Component
public class SchemaInvalidationHandler {

    private static final Logger log = LoggerFactory.getLogger(SchemaInvalidationHandler.class);

    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    /** version 乱序水位线：{domain}:{code} → 已处理的最大 version（BC-05） */
    private final ConcurrentMap<String, Long> versionWatermarks = new ConcurrentHashMap<>();

    public SchemaInvalidationHandler(CacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理一条广播消息（尽力而为，任何异常仅 WARN）。
     *
     * @param messageJson 消息体 {@code {event_id, event_type, timestamp, payload:{...}}}
     */
    public void handle(String messageJson) {
        try {
            JsonNode root = objectMapper.readTree(messageJson);
            JsonNode payload = root.path("payload");
            String domain = payload.path("cache_domain").asText(null);
            String code = payload.path("code").asText(null);
            long version = payload.path("version").asLong(-1L);
            if (domain == null || domain.isEmpty()) {
                log.warn("[schema-invalidation] 消息缺少 cache_domain，丢弃: eventId={}",
                        root.path("event_id").asText());
                return;
            }
            // BC-05：version 乱序过滤（menu_config 全局键无版本单调性要求，仍按水位线处理）
            String watermarkKey = domain + ":" + code;
            Long watermark = versionWatermarks.get(watermarkKey);
            if (watermark != null && version >= 0 && version < watermark) {
                log.debug("[schema-invalidation] 乱序消息跳过: key={}, version={}, watermark={}",
                        watermarkKey, version, watermark);
                return;
            }
            if (version >= 0) {
                versionWatermarks.merge(watermarkKey, version, Math::max);
            }
            String cacheKey = toCacheKey(domain, code);
            if (cacheKey == null) {
                log.debug("[schema-invalidation] 未知缓存域，忽略: domain={}", domain);
                return;
            }
            cacheService.evict(cacheKey);
            log.info("[schema-invalidation] 缓存失效完成: domain={}, code={}, version={}",
                    domain, code, version);
        } catch (IOException | RuntimeException e) {
            // BC-04：失败放弃（不抛回消费线程，避免无限重投；TTL 兜底最终一致）
            log.warn("[schema-invalidation] 消息处理失败，放弃: cause={}", e.getMessage());
        }
    }

    /** cache_domain + code → 缓存键（未知域返回 null） */
    static String toCacheKey(String domain, String code) {
        switch (domain) {
            case CacheDomains.ENTITY_SCHEMA:
                return CacheKeyBuilder.entitySchema(code);
            case CacheDomains.DOC_SCHEMA:
                return CacheKeyBuilder.docSchema(code);
            case CacheDomains.STATE_MACHINE:
                return CacheKeyBuilder.stateMachine(code);
            case CacheDomains.RULE_LIB:
                return CacheKeyBuilder.rule(code);
            case CacheDomains.DOC_RULE_CONFIG:
                return CacheKeyBuilder.docRuleConfig(code);
            case CacheDomains.TX_CONFIG:
                return CacheKeyBuilder.txConfig(code);
            case CacheDomains.MENU_CONFIG:
                return CacheKeyBuilder.menuTree();
            default:
                return null;
        }
    }

    /** 构造广播消息体 JSON（与 RocketMqSchemaInvalidationPublisher 同源结构） */
    static Map<String, Object> messageBody(String eventId, String eventType, long timestamp,
                                           String domain, String code, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cache_domain", domain);
        payload.put("code", code);
        payload.put("version", version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", eventId);
        body.put("event_type", eventType);
        body.put("timestamp", timestamp);
        body.put("payload", payload);
        return body;
    }
}
