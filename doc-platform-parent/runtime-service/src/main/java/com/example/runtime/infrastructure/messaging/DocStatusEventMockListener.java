package com.example.runtime.infrastructure.messaging;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.messaging.DocMessage;
import com.example.common.infrastructure.messaging.DocMessageListener;
import com.example.common.infrastructure.messaging.MessageMode;
import com.example.runtime.domain.event.DocStatusEventConsumer;

/**
 * doc-status-events mock 模式消费监听器（门面 DocMessageListener 实现）。
 *
 * <p>mock 模式下由 InMemoryMessageBroker 进程内分发（FIFO 语义近似：
 * 同一 shardingKey 单线程顺序分发）；rocketmq 模式不装配本 Bean，
 * 由 {@link DocStatusEventRocketListener} 承接真实顺序消费。
 * 依赖 domain 端口 {@link DocStatusEventConsumer}（依赖倒置，
 * infrastructure 不直接依赖 service 层）。</p>
 */
public class DocStatusEventMockListener implements DocMessageListener {

    private final DocStatusEventConsumer consumer;

    public DocStatusEventMockListener(DocStatusEventConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public String topic() {
        return DocMqConstants.Topics.DOC_STATUS_EVENTS;
    }

    @Override
    public String consumerGroup() {
        return DocMqConstants.Groups.RUNTIME_DOC_STATUS;
    }

    @Override
    public MessageMode mode() {
        return MessageMode.ORDERLY;
    }

    @Override
    public void onMessage(DocMessage message) {
        consumer.consume(message.getBody(), message.getUserProperties(), "mock");
    }
}
