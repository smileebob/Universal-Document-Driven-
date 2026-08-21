package com.example.meta.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * RocketMQ 失效广播真实装配条件（阶段三双模式扩展）：
 * {@code doc.mq.mode=rocketmq} 且 {@code rocketmq.name-server} 非空 且
 * {@code doc.mq.schema-invalidation.enabled} 非 false（默认启用）时匹配。
 *
 * <p>未匹配时：@RocketMQMessageListener 消费容器不装配；发布链路由
 * {@link FacadeSchemaInvalidationPublisher} 经 MQ 门面承接（mock 模式进程内+
 * Redis 桥接），无任何发布器时 NoOp 兜底（WARN + Caffeine TTL≤5min，BC-03）。</p>
 */
class RocketMqAvailableCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("doc.mq.mode", "mock");
        String nameServer = context.getEnvironment().getProperty("rocketmq.name-server");
        String enabled = context.getEnvironment().getProperty("doc.mq.schema-invalidation.enabled");
        boolean rocketMqMode = "rocketmq".equalsIgnoreCase(mode.trim());
        boolean configured = nameServer != null && !nameServer.isBlank();
        boolean switchedOn = enabled == null || !"false".equalsIgnoreCase(enabled.trim());
        if (rocketMqMode && configured && switchedOn) {
            return ConditionOutcome.match("doc.mq.mode=rocketmq 且 name-server 已配置且失效广播开关开启");
        }
        return ConditionOutcome.noMatch(
                "RocketMQ 真实装配条件未满足（mode=" + mode + ", configured=" + configured
                        + ", switchedOn=" + switchedOn + "），走 MQ 门面 mock/NoOp 链路");
    }
}
