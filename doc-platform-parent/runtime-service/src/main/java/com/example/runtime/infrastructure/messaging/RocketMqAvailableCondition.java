package com.example.runtime.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * RocketMQ 消费装配条件（与 meta-service 同构，design/06 §5.3）：
 * {@code doc.mq.mode=rocketmq} 且 {@code rocketmq.name-server} 非空时匹配
 * （阶段三双模式改造：mode 缺省 mock 时不装配任何 RocketMQ 消费容器）。
 *
 * <p>未匹配时：消息消费经 mock 门面（InMemoryMessageBroker + Redis Pub/Sub 桥接），
 * schema 缓存另有 L2/L1 TTL 兜底过期（BC-03）。</p>
 */
class RocketMqAvailableCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("doc.mq.mode", "mock");
        if (!"rocketmq".equalsIgnoreCase(mode.trim())) {
            return ConditionOutcome.noMatch("doc.mq.mode=" + mode + "（非 rocketmq），消费走 mock 门面");
        }
        String nameServer = context.getEnvironment().getProperty("rocketmq.name-server");
        boolean configured = nameServer != null && !nameServer.isBlank();
        if (configured) {
            return ConditionOutcome.match("doc.mq.mode=rocketmq 且 name-server 已配置");
        }
        return ConditionOutcome.noMatch("doc.mq.mode=rocketmq 但 rocketmq.name-server 未配置，降级 mock");
    }
}
