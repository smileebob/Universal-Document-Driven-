package com.example.common.infrastructure.messaging;

import java.util.List;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * MQ 门面双模式自动装配（任务清单 1：{@code doc.mq.mode=mock|rocketmq}）。
 *
 * <ul>
 *   <li>mock（缺省）：{@link InMemoryMessageBroker} + {@link MockDocMessagePublisher}，
 *       classpath 有 Redis 时追加 {@link MockMqRedisBridge} 跨实例桥接；</li>
 *   <li>rocketmq：{@link RocketMqDocMessagePublisher}（依赖官方自动装配的
 *       {@link RocketMQTemplate}；name-server 缺失时
 *       {@link RocketMqOptionalPostProcessor} 已排除官方装配，本 Bean 不生效，
 *       由 {@code @ConditionalOnMissingBean} 兜底回落 mock）。</li>
 * </ul>
 *
 * <p>业务代码只依赖 {@link DocMessagePublisher}/{@link DocMessageListener}
 * 门面（F-06 防腐），对具体 MQ 实现零感知。</p>
 *
 * <p>可选依赖（spring-data-redis / rocketmq-spring）的 Bean 全部收敛于
 * 嵌套配置类并由类级 {@code @ConditionalOnClass} 守护：无对应 jar 的应用
 * （如无 Redis/RocketMQ 的 WebFlux 网关）不会加载嵌套类，本配置类方法
 * 签名不直接引用可选类型，避免 Bean 类型内省触发
 * {@code NoClassDefFoundError} 导致启动失败。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocMqProperties.class)
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "org.redisson.spring.starter.RedissonAutoConfiguration"
})
public class DocMqAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocMqAutoConfiguration.class);

    /**
     * 生产模式守卫：prod 必须 rocketmq 且配置接入点，禁止任何 mock 降级（ENV-02）。
     */
    @Bean
    @Profile("prod")
    public DocMqProductionGuard docMqProductionGuard(DocMqProperties properties,
                                                     Environment environment) {
        return new DocMqProductionGuard(properties, environment);
    }

    /** mock 模式消息 Broker（缺省模式；prod 不装配，避免静默仿真） */
    @Bean
    @Profile("!prod")
    @ConditionalOnProperty(prefix = "doc.mq", name = "mode", havingValue = "mock", matchIfMissing = true)
    public InMemoryMessageBroker inMemoryMessageBroker(ObjectProvider<DocMessageListener> listeners) {
        return new InMemoryMessageBroker(listeners.orderedStream().toList());
    }

    /** mock 模式发布器（缺省模式；prod 不装配） */
    @Bean
    @Order(1)
    @Profile("!prod")
    @ConditionalOnProperty(prefix = "doc.mq", name = "mode", havingValue = "mock", matchIfMissing = true)
    @ConditionalOnMissingBean(DocMessagePublisher.class)
    public DocMessagePublisher mockDocMessagePublisher(InMemoryMessageBroker broker,
                                                       ObjectProvider<MockMqRedisBridge> bridgeProvider) {
        log.warn("[mq] 装配 mock 进程内消息仿真（仅限 dev/test；生产禁止）");
        // bridge may be absent in apps without spring-data-redis (e.g. WebFlux gateway): degrade to in-process
        return new MockDocMessagePublisher(broker, bridgeProvider.getIfAvailable());
    }

    /**
     * 开发兜底：mode=rocketmq 但 RocketMQTemplate 不可用时降级 mock 保启动。
     * prod 下不装配，缺真实发布器将启动失败（DocMqProductionGuard/必需依赖）。
     */
    @Bean
    @Order(2)
    @Profile("!prod")
    @ConditionalOnMissingBean(DocMessagePublisher.class)
    public DocMessagePublisher fallbackMockPublisher(ObjectProvider<DocMessageListener> listeners,
                                                     ObjectProvider<MockMqRedisBridge> bridge) {
        log.warn("[mq] doc.mq.mode=rocketmq 但 RocketMQTemplate 不可用，降级 mock 进程内仿真（仅限 dev/test）");
        return new MockDocMessagePublisher(new InMemoryMessageBroker(listeners.orderedStream().toList()),
                bridge.getIfAvailable());
    }

    /** 已注册监听器只读视图（诊断端点/测试用，mock 模式） */
    @Bean
    @Profile("!prod")
    @ConditionalOnProperty(prefix = "doc.mq", name = "mode", havingValue = "mock", matchIfMissing = true)
    public MockMqInspector mockMqInspector(InMemoryMessageBroker broker) {
        return new MockMqInspector(broker);
    }

    /**
     * rocketmq 模式真实发布器装配（仅 classpath 有 rocketmq-spring 时加载）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RocketMQTemplate.class)
    @ConditionalOnProperty(prefix = "doc.mq", name = "mode", havingValue = "rocketmq")
    static class RocketMqPublisherConfiguration {

        /** rocketmq 模式发布器（真实装配，生产口径） */
        @Bean
        @ConditionalOnBean(RocketMQTemplate.class)
        public DocMessagePublisher rocketMqDocMessagePublisher(RocketMQTemplate rocketMQTemplate) {
            log.info("[mq] doc.mq.mode=rocketmq：装配真实 RocketMQ 发布器");
            return new RocketMqDocMessagePublisher(rocketMQTemplate);
        }
    }

    /**
     * Redis Pub/Sub cross-instance bridge (loaded only when spring-data-redis is on classpath).
     * StringRedisTemplate is resolved lazily via ObjectProvider at bean instantiation time:
     * Redis autoconfigurations may register after this one, so definition-time
     * ConditionalOnBean(StringRedisTemplate) can misfire; at instantiation time all
     * definitions are ready and the template is created on demand. When the template
     * is absent the bridge degrades to in-process simulation with a WARN (BC-03).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisBridgeConfiguration {

        /** mock-mode Redis cross-instance bridge (degrades to in-process when template missing) */
        @Bean
        @Profile("!prod")
        @ConditionalOnProperty(prefix = "doc.mq", name = "mode", havingValue = "mock", matchIfMissing = true)
        public MockMqRedisBridge mockMqRedisBridge(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                   InMemoryMessageBroker broker) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                log.warn("[mock-mq] StringRedisTemplate unavailable, bridge degrades to in-process only (TTL fallback, BC-03)");
                return new MockMqRedisBridge(null, broker, null);
            }
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(redisTemplate.getConnectionFactory());
            container.afterPropertiesSet();
            container.start();
            return new MockMqRedisBridge(redisTemplate, broker, container);
        }
    }

    /** mock 消息留痕查询（诊断用薄封装） */
    public static class MockMqInspector {

        private final InMemoryMessageBroker broker;

        public MockMqInspector(InMemoryMessageBroker broker) {
            this.broker = broker;
        }

        public List<DocMessage> records() {
            return broker.records();
        }

        public List<DocMessage> recordsOf(String topic) {
            return broker.recordsOf(topic);
        }
    }
}
