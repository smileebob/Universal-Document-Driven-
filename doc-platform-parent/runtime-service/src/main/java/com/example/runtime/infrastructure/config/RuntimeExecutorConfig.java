package com.example.runtime.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 运行域线程池装配。
 *
 * <p>auditExecutor：审计异步旁路专用池（RD-12）。旁路语义下队列满时
 * 退化为调用方执行（CallerRunsPolicy）而非丢弃，最大限度保审计；
 * 与请求线程池隔离，审计写入抖动不影响 Web 容器线程。</p>
 */
@Configuration
public class RuntimeExecutorConfig {

    /**
     * 审计异步写入线程池。
     *
     * @return 审计专用执行器
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
