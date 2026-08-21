package com.example.meta.infrastructure.messaging;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.example.common.infrastructure.messaging.DocMqAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.example.common.infrastructure.messaging.DocMessagePublisher;
import com.example.common.infrastructure.messaging.InMemoryMessageBroker;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;

/**
 * 失效广播装配配置（阶段三双模式，design/06 §5.3 / 任务清单 5）。
 *
 * <ul>
 *   <li>mock 模式（缺省）：{@link FacadeSchemaInvalidationPublisher} 经
 *       doc-common MQ 门面发布（进程内分发 + Redis 桥接跨实例），
 *       {@link SchemaInvalidationMockListener} 承接本实例消费；</li>
 *   <li>rocketmq 模式（name-server 齐全）：保留 RocketMQTemplate 直连发布器 +
 *       BROADCASTING 消费容器（既有链路）；</li>
 *   <li>均不可用：{@link NoOpSchemaInvalidationPublisher} 兜底（WARN +
 *       Caffeine TTL≤5min，BC-03），不阻塞启动。</li>
 * </ul>
 *
 * <p>注意：@ConditionalOnMissingBean 在同配置类内按 Bean 定义顺序评估，
 * rocketmq 直连发布器定义在前，mock 门面发布器定义在后，保证 rocketmq 模式
 * 下门面发布器不重复装配。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(DocMqAutoConfiguration.class)
public class SchemaInvalidationConfiguration {

    /** RocketMQ 真实发布器（条件装配：rocketmq 模式 + name-server 齐全） */
    @Bean
    @Conditional(RocketMqAvailableCondition.class)
    public SchemaInvalidationPublisher rocketMqSchemaInvalidationPublisher(
            RocketMQTemplate rocketMQTemplate) {
        return new RocketMqSchemaInvalidationPublisher(rocketMQTemplate);
    }

    /** BROADCASTING 消费者（rocketmq 模式，与发布器同条件） */
    @Bean
    @Conditional(RocketMqAvailableCondition.class)
    public SchemaInvalidationBroadcastListener schemaInvalidationBroadcastListener(
            SchemaInvalidationHandler handler) {
        return new SchemaInvalidationBroadcastListener(handler);
    }

    /** mock 模式门面发布器（MQ 门面发布器存在且 rocketmq 直连未装配时生效） */
    @Bean
    @ConditionalOnBean(DocMessagePublisher.class)
    @ConditionalOnMissingBean(SchemaInvalidationPublisher.class)
    public SchemaInvalidationPublisher facadeSchemaInvalidationPublisher(
            DocMessagePublisher messagePublisher) {
        return new FacadeSchemaInvalidationPublisher(messagePublisher);
    }

    /** mock 模式本实例消费器（仅 mock 模式 Broker 存在时装配，与 rocketmq 容器互斥） */
    @Bean
    @ConditionalOnBean(InMemoryMessageBroker.class)
    public SchemaInvalidationMockListener schemaInvalidationMockListener(
            SchemaInvalidationHandler handler) {
        return new SchemaInvalidationMockListener(handler);
    }

    /** NoOp 兜底发布器（无任何真实发布器时生效） */
    @Bean
    @ConditionalOnMissingBean(SchemaInvalidationPublisher.class)
    public SchemaInvalidationPublisher noOpSchemaInvalidationPublisher() {
        return new NoOpSchemaInvalidationPublisher();
    }
}
