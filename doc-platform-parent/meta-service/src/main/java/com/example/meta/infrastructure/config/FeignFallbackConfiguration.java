package com.example.meta.infrastructure.config;

import com.example.runtime.api.fallback.DataRefClientFallbackFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 降级工厂注册（P-04：FallbackFactory 全配）。
 *
 * <p>契约模块（runtime-service-api）不在本服务组件扫描路径内，
 * 其 FallbackFactory 由本配置类显式注册为 Bean；
 * feign.sentinel.enabled 启用后经 Sentinel 集成生效。</p>
 */
@Configuration
public class FeignFallbackConfiguration {

    /**
     * runtime-service DataRefClient 降级工厂。
     *
     * @return 降级工厂实例
     */
    @Bean
    public DataRefClientFallbackFactory dataRefClientFallbackFactory() {
        return new DataRefClientFallbackFactory();
    }
}
