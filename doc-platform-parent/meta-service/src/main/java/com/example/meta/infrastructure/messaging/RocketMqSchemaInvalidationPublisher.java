package com.example.meta.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import com.example.common.constants.DocMqConstants;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;

/**
 * RocketMQ 失效广播发布器（条件装配：仅在 name-server 配置齐全时生效）。
 *
 * <p>BC-03 尽力而为：发送失败仅 WARN 不阻断业务写（Caffeine TTL≤5min 兜底）。
 * Tag = cache_domain（design/07 §2.4 Broker 端过滤），destination 形如
 * {@code schema-invalidation:entity_schema}。</p>
 */
public class RocketMqSchemaInvalidationPublisher implements SchemaInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqSchemaInvalidationPublisher.class);

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMqSchemaInvalidationPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void publish(SchemaInvalidationEvent event) {
        String destination = DocMqConstants.Topics.SCHEMA_INVALIDATION + ":" + event.getCacheDomain();
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    SchemaInvalidationHandler.messageBody(event.getEventId(), event.getEventType(),
                            event.getTimestamp(), event.getCacheDomain(), event.getCode(),
                            event.getVersion()));
            rocketMQTemplate.syncSend(destination, json);
            log.info("[schema-invalidation] 广播发送成功: {}", event);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[schema-invalidation] 广播发送失败（TTL 兜底，不阻断业务）: event={}, cause={}",
                    event, e.getMessage());
        }
    }
}
