package com.example.common.infrastructure.cache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 两级缓存自动装配（阶段二自 meta-service 下沉至 doc-common）。
 *
 * <p>条件：容器存在 {@link StringRedisTemplate}（即引入 spring-boot-starter-data-redis
 * 的服务：meta-service / runtime-service）才装配；网关等无 Redis 模块不受影响。
 * Redis 不可达时 CacheService 内部自动降级为 Caffeine + 直读回源（BC-03 TTL 兜底）。</p>
 *
 * <p>可观测性（任务 #20 / design/11 §4.2）：容器存在 MeterRegistry 时为缓存
 * L1/L2 命中计数打点（ddp_cache_get_total）。</p>
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    /** 两级缓存服务（meta/runtime 共用骨架） */
    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(CacheService.class)
    public CacheService cacheService(CacheProperties properties, StringRedisTemplate redisTemplate,
                                     ObjectProvider<MeterRegistry> registryProvider) {
        CacheService cacheService = new CacheService(properties, redisTemplate);
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            cacheService.bindMetrics(registry);
        }
        return cacheService;
    }
}
