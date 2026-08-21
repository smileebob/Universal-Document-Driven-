package com.example.common.infrastructure.messaging;

import java.util.Map;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;

/**
 * rocketmq 模式消息发布器（doc.mq.mode=rocketmq，rocketmq-spring-boot-starter 真实投递）。
 *
 * <p>翻译规则：destination = {@code topic:tag}；ORDERLY 经
 * {@code syncSendOrderly}（hashKey = shardingKey，阿里云 5.x 托管 FIFO
 * 语义按 Sharding Key 分区保序）；userProperties 经 MessageBuilder headers
 * 透传（traceparent 等，design/07 §3.2）。发送失败返回 false 不抛异常，
 * 由调用方走 Outbox 退避兜底。</p>
 */
public class RocketMqDocMessagePublisher implements DocMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqDocMessagePublisher.class);

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMqDocMessagePublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public boolean publish(DocMessage message) {
        if (message == null || message.getTopic() == null) {
            return false;
        }
        try {
            MessageBuilder<String> builder = MessageBuilder.withPayload(
                    message.getBody() == null ? "" : message.getBody());
            for (Map.Entry<String, String> entry : message.getUserProperties().entrySet()) {
                builder.setHeader(entry.getKey(), entry.getValue());
            }
            if (message.getMode() == MessageMode.ORDERLY) {
                String hashKey = message.getShardingKey() == null || message.getShardingKey().isBlank()
                        ? message.getTopic() : message.getShardingKey();
                rocketMQTemplate.syncSendOrderly(message.destination(), builder.build(), hashKey);
            } else {
                rocketMQTemplate.syncSend(message.destination(), builder.build());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("[rocketmq] 发送失败: message={}, cause={}", message, e.getMessage());
            return false;
        }
    }

    @Override
    public String mode() {
        return "rocketmq";
    }
}
