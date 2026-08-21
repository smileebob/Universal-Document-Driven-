package com.example.runtime.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.example.common.infrastructure.messaging.DocMessageListener;
import com.example.common.infrastructure.messaging.InMemoryMessageBroker;
import com.example.runtime.domain.event.DocStatusEventConsumer;

/**
 * runtime 侧消息消费装配（阶段三双模式，design/06 §5.3 / design/07）：
 * <ul>
 *   <li>{@code doc.mq.mode=rocketmq} 且 name-server 已配置：装配 BROADCASTING
 *       失效广播容器 + doc-status-events ORDERLY 消费容器；</li>
 *   <li>否则（缺省 mock）：装配门面监听器 Bean，由 doc-common
 *       InMemoryMessageBroker 进程内分发（跨实例经 Redis Pub/Sub 桥接），
 *       schema 缓存另有 L2/L1 TTL 兜底过期（BC-03）。</li>
 * </ul>
 *
 * <p>runtime 仅为消费方，不发布失效广播（发布方是 meta-service）。
 * 消费监听器统一依赖 domain 端口 {@link DocStatusEventConsumer}
 * （依赖倒置，ArchUnit 分层门禁）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeMessagingConfiguration {

    // ==================== rocketmq 模式（条件装配） ====================

    /** BROADCASTING 失效广播消费容器（rocketmq 模式） */
    @Bean
    @Conditional(RocketMqAvailableCondition.class)
    public SchemaCacheInvalidationListener schemaCacheInvalidationListener(
            SchemaCacheInvalidationHandler handler) {
        return new SchemaCacheInvalidationListener(handler);
    }

    /** doc-status-events ORDERLY 消费容器（rocketmq 模式） */
    @Bean
    @Conditional(RocketMqAvailableCondition.class)
    public DocStatusEventRocketListener docStatusEventRocketListener(
            DocStatusEventConsumer consumer) {
        return new DocStatusEventRocketListener(consumer);
    }

    // ==================== mock 模式（门面监听器） ====================

    /** mock 失效广播监听器（经 InMemoryMessageBroker 分发，跨实例 Redis 桥接） */
    @Bean
    @ConditionalOnBean(InMemoryMessageBroker.class)
    public DocMessageListener runtimeSchemaInvalidationMockListener(
            SchemaCacheInvalidationHandler handler) {
        return new SchemaInvalidationMockListener(handler);
    }

    /** mock doc-status-events 监听器（FIFO 语义近似） */
    @Bean
    @ConditionalOnBean(InMemoryMessageBroker.class)
    @ConditionalOnMissingBean(DocStatusEventRocketListener.class)
    public DocMessageListener docStatusEventMockListener(
            DocStatusEventConsumer consumer) {
        return new DocStatusEventMockListener(consumer);
    }
}
