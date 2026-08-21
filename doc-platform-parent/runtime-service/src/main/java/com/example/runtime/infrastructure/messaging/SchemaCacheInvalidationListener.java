package com.example.runtime.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import com.example.common.constants.DocMqConstants;

/**
 * runtime 侧 schema 失效广播消费者（BROADCASTING 模式，design/06 §5.3 / design/07 §3.3）。
 *
 * <p>每个 runtime 实例独立消费全量广播，本地 DEL 两级缓存键；业务逻辑在
 * {@link SchemaCacheInvalidationHandler}（纯类可单测）。消费异常仅 WARN 不重投
 * （BC-04 失败≤3 次放弃，TTL 兜底）。</p>
 *
 * <p>条件装配：仅当 RocketMQ 可用（{@link RocketMqAvailableCondition}）时由
 * {@link RuntimeMessagingConfiguration} 注册为 Bean；未配置时不产生消费容器，
 * 启动不受影响（无 MQ 时 TTL 兜底）。</p>
 */
@RocketMQMessageListener(
        topic = DocMqConstants.Topics.SCHEMA_INVALIDATION,
        consumerGroup = DocMqConstants.Groups.RUNTIME_SCHEMA_INVALIDATION_BROADCAST,
        selectorExpression = DocMqConstants.Tags.ALL,
        messageModel = MessageModel.BROADCASTING)
public class SchemaCacheInvalidationListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(SchemaCacheInvalidationListener.class);

    private final SchemaCacheInvalidationHandler handler;

    public SchemaCacheInvalidationListener(SchemaCacheInvalidationHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onMessage(String message) {
        try {
            handler.handle(message);
        } catch (RuntimeException e) {
            // BC-04：失败放弃，不抛回消费线程（避免无限重投）
            log.warn("[runtime-schema-invalidation] 广播消费失败，放弃: cause={}", e.getMessage());
        }
    }
}
