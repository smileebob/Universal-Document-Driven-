package com.example.meta.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessageListener;
import com.example.common.infrastructure.messaging.MessageMode;

/**
 * meta 侧失效广播 mock 模式消费器（doc.mq.mode=mock 时由 InMemoryMessageBroker 分发；
 * rocketmq 模式由 {@link SchemaInvalidationBroadcastListener} @RocketMQMessageListener 承接，
 * 本 Bean 不装配——两者互斥，条件见 {@link SchemaInvalidationConfiguration}）。
 *
 * <p>BC-01~06 全部落 {@link SchemaInvalidationHandler}（幂等/自包含/TTL 兜底/
 * 失败≤3次放弃/version 水位线/不级联写）。</p>
 */
public class SchemaInvalidationMockListener implements DocMessageListener {

    private static final Logger log = LoggerFactory.getLogger(SchemaInvalidationMockListener.class);

    private final SchemaInvalidationHandler handler;

    public SchemaInvalidationMockListener(SchemaInvalidationHandler handler) {
        this.handler = handler;
    }

    @Override
    public String topic() {
        return DocMqConstants.Topics.SCHEMA_INVALIDATION;
    }

    @Override
    public String consumerGroup() {
        return DocMqConstants.Groups.META_SCHEMA_INVALIDATION_BROADCAST;
    }

    @Override
    public MessageMode mode() {
        return MessageMode.BROADCAST;
    }

    @Override
    public void onMessage(DocMessage message) {
        log.debug("[schema-invalidation] meta mock 消费: {}", message);
        handler.handle(message.getBody());
    }
}
