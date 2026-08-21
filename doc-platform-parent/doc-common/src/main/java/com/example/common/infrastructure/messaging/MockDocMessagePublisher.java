package com.example.common.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mock 模式消息发布器（doc.mq.mode=mock）。
 *
 * <p>发布路径：① broker 留痕 → ② 本进程监听器分发（发布方自消费，
 * 近似「同实例广播/FIFO 送达」）→ ③ Redis 桥接跨实例扇出（可选，
 * Redis 不可达降级为纯进程内）。</p>
 */
public class MockDocMessagePublisher implements DocMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MockDocMessagePublisher.class);

    private final InMemoryMessageBroker broker;
    private final MockMqRedisBridge bridge;

    public MockDocMessagePublisher(InMemoryMessageBroker broker, MockMqRedisBridge bridge) {
        this.broker = broker;
        this.bridge = bridge;
        log.info("[mock-mq] MockDocMessagePublisher 就绪（bridge={}）", bridge == null ? "无" : "Redis");
    }

    @Override
    public boolean publish(DocMessage message) {
        if (message == null || message.getTopic() == null) {
            return false;
        }
        try {
            broker.record(message);
            broker.dispatchLocal(message);
            if (bridge != null) {
                bridge.publish(message);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("[mock-mq] 发布失败: message={}, cause={}", message, e.getMessage());
            return false;
        }
    }

    @Override
    public String mode() {
        return "mock";
    }
}
