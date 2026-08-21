package com.example.meta.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;

/**
 * NoOp 失效广播发布器（RocketMQ 未配置/关闭时的兜底装配）。
 *
 * <p>BC-03 降级语义：跨实例失效依赖 Caffeine TTL≤5min 自然过期兜底；
 * 本实例写路径仍执行 Redis DEL，单实例一致性不受影响。首次调用打 WARN
 * 提示运维当前处于无广播降级模式。</p>
 */
public class NoOpSchemaInvalidationPublisher implements SchemaInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpSchemaInvalidationPublisher.class);

    /** 只提示一次，避免日志刷屏 */
    private volatile boolean warned;

    @Override
    public void publish(SchemaInvalidationEvent event) {
        if (!warned) {
            warned = true;
            log.warn("[schema-invalidation] RocketMQ 未配置，广播发布降级为 NoOp："
                    + "跨实例缓存失效依赖 L2 TTL≤5min 兜底（BC-03）");
        }
        log.debug("[schema-invalidation] NoOp 丢弃广播事件: {}", event);
    }
}
