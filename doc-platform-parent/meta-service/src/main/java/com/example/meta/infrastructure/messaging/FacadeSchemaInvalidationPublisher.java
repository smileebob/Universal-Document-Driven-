package com.example.meta.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessagePublisher;
import com.example.common.infrastructure.messaging.MessageMode;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MQ 门面失效广播发布器（阶段三双模式统一出口，替代 RocketMQTemplate 直连）。
 *
 * <p>消息体与 {@link SchemaInvalidationHandler#messageBody} 同源结构；
 * Tag = cache_domain（design/07 §2.4），mode = BROADCAST。底层由
 * {@link DocMessagePublisher} 决定传输（mock=进程内+Redis 桥接，
 * rocketmq=RocketMQTemplate）。BC-03 尽力而为：发送失败仅 WARN 不阻断业务。</p>
 */
public class FacadeSchemaInvalidationPublisher implements SchemaInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(FacadeSchemaInvalidationPublisher.class);

    private final DocMessagePublisher messagePublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FacadeSchemaInvalidationPublisher(DocMessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    @Override
    public void publish(SchemaInvalidationEvent event) {
        try {
            String json = objectMapper.writeValueAsString(
                    SchemaInvalidationHandler.messageBody(event.getEventId(), event.getEventType(),
                            event.getTimestamp(), event.getCacheDomain(), event.getCode(),
                            event.getVersion()));
            DocMessage message = new DocMessage(DocMqConstants.Topics.SCHEMA_INVALIDATION,
                    event.getCacheDomain(), null, json, MessageMode.BROADCAST)
                    .withUserProperty("event_id", event.getEventId())
                    .withUserProperty("event_type", event.getEventType());
            boolean sent = messagePublisher.publish(message);
            if (sent) {
                log.info("[schema-invalidation] 广播发送成功（{}）: {}", messagePublisher.mode(), event);
            } else {
                log.warn("[schema-invalidation] 广播发送失败（TTL 兜底，不阻断业务）: event={}", event);
            }
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("[schema-invalidation] 广播发送异常（TTL 兜底，不阻断业务）: event={}, cause={}",
                    event, e.getMessage());
        }
    }
}
