package com.example.runtime.infrastructure.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 后台任务线程池治理（G-01，design/08 §3.3）：
 * 显式命名线程池 + 有界队列 + 拒绝策略 + traceId（MDC）传播。
 *
 * <ul>
 *   <li>{@code outboxRelayExecutor}：Outbox 中继即时首发 worker（队列满 CallerRuns
 *       退化为调用方投递，保事件不丢）；</li>
 *   <li>{@code messagingBgExecutor}：扫描兜底/归档清理等后台旁路（队列满 AbortPolicy
 *       拒绝并 WARN——定时任务下轮兜底，不阻塞调度线程）。</li>
 * </ul>
 */
@Configuration
public class MessagingExecutorConfig {

    /**
     * Outbox 中继投递线程池（EV-01 即时首发 worker）。
     *
     * @return 中继投递执行器
     */
    @Bean("outboxRelayExecutor")
    public Executor outboxRelayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("outbox-relay-");
        // 队列满退化为调用方执行（事务已提交，事件不可丢，宁可慢不可丢）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(mdcDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 消息后台任务线程池（扫描兜底/归档清理/消费证据旁路）。
     *
     * @return 后台任务执行器
     */
    @Bean("messagingBgExecutor")
    public Executor messagingBgExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("messaging-bg-");
        // 后台旁路允许丢弃（定时任务下轮兜底；AbortPolicy 由调用方捕获 WARN）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(mdcDecorator());
        executor.initialize();
        return executor;
    }

    /** MDC 上下文传播装饰器（traceId 跨线程透传，G-01） */
    private TaskDecorator mdcDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
