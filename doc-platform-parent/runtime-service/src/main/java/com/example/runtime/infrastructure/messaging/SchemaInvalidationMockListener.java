package com.example.runtime.infrastructure.messaging;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessageListener;
import com.example.common.infrastructure.messaging.MessageMode;

/**
 * runtime 侧 schema 失效广播 mock 模式监听器（门面 DocMessageListener 实现）。
 *
 * <p>mock 模式下由 InMemoryMessageBroker 进程内分发（跨实例经 Redis Pub/Sub
 * 桥接，BC-01~06 约束落 {@link SchemaCacheInvalidationHandler}）；rocketmq
 * 模式不装配本 Bean，由 {@link SchemaCacheInvalidationListener} 承接
 * BROADCASTING 真实消费。</p>
 */
public class SchemaInvalidationMockListener implements DocMessageListener {

    private final SchemaCacheInvalidationHandler handler;

    public SchemaInvalidationMockListener(SchemaCacheInvalidationHandler handler) {
        this.handler = handler;
    }

    @Override
    public String topic() {
        return DocMqConstants.Topics.SCHEMA_INVALIDATION;
    }

    @Override
    public String consumerGroup() {
        return DocMqConstants.Groups.RUNTIME_SCHEMA_INVALIDATION_BROADCAST;
    }

    @Override
    public MessageMode mode() {
        return MessageMode.BROADCAST;
    }

    @Override
    public void onMessage(DocMessage message) {
        handler.handle(message.getBody());
    }
}
