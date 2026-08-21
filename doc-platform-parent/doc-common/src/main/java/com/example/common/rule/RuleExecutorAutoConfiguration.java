package com.example.common.rule;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 规则执行器自动装配（doc-common，Boot 3 imports 注册）。
 *
 * <p>条件：classpath 存在 SpEL（{@link SpelExpressionParser}，服务侧经
 * spring-context 必然具备）；meta-service / runtime-service 共用同一执行器，
 * 保证在线测试与运行时语义一致（design/08 §四）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(RuleExecutorProperties.class)
@ConditionalOnClass(SpelExpressionParser.class)
public class RuleExecutorAutoConfiguration {

    /** 规则执行器（销毁时关闭求值线程池） */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RuleExecutor.class)
    public RuleExecutor ruleExecutor(RuleExecutorProperties properties) {
        return new RuleExecutor(properties.getTimeoutMillis(),
                properties.getPoolCoreSize(), properties.getPoolMaxSize(),
                properties.getQueueCapacity(), properties.getCacheCapacity());
    }
}
