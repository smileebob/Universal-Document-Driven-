package com.example.runtime.infrastructure.messaging;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.common.constants.DocMqConstants;
import com.example.runtime.domain.event.DocStatusEventConsumer;

/**
 * doc-status-events RocketMQ 顺序消费监听器（集群 + ORDERLY，design/07 §2.2）。
 *
 * <p>消费失败抛异常：ORDERLY 语义下 broker 挂起该队列稍后重投
 * （顺序保障优先，EV-02 重试语义）；event_id 去重（Redis SETNX 24h）
 * 保证重投不产生副作用。条件装配：仅 doc.mq.mode=rocketmq 且
 * name-server 已配置时注册（{@link RocketMqAvailableCondition}）。
 * 依赖 domain 端口 {@link DocStatusEventConsumer}（依赖倒置）。</p>
 */
@RocketMQMessageListener(
        topic = DocMqConstants.Topics.DOC_STATUS_EVENTS,
        consumerGroup = DocMqConstants.Groups.RUNTIME_DOC_STATUS,
        selectorExpression = DocMqConstants.Tags.ALL,
        messageModel = MessageModel.CLUSTERING,
        consumeMode = ConsumeMode.ORDERLY)
public class DocStatusEventRocketListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(DocStatusEventRocketListener.class);

    private final DocStatusEventConsumer consumer;

    public DocStatusEventRocketListener(DocStatusEventConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public void onMessage(String message) {
        try {
            // userProperties 经消息体信封携带（event_id 根字段），去重器内部兜底解析
            consumer.consume(message, null, "rocketmq");
        } catch (RuntimeException e) {
            // 抛出触发 ORDERLY 重投（顺序优先语义）
            log.warn("[doc-status-consume] 消费失败，等待 broker 重投: cause={}", e.getMessage());
            throw e;
        }
    }
}
