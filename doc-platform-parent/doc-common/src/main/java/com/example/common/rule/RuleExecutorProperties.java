package com.example.common.rule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规则执行器配置（doc.rule.*，design/08 §四）。
 */
@ConfigurationProperties(prefix = "doc.rule")
public class RuleExecutorProperties {

    /** 单条规则求值超时（毫秒） */
    private long timeoutMillis = 2000L;

    /** 求值线程池核心线程数 */
    private int poolCoreSize = 2;

    /** 求值线程池最大线程数 */
    private int poolMaxSize = 4;

    /** 求值线程池队列容量 */
    private int queueCapacity = 64;

    /** 编译缓存容量（LRU 有界） */
    private int cacheCapacity = 512;

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getPoolCoreSize() {
        return poolCoreSize;
    }

    public void setPoolCoreSize(int poolCoreSize) {
        this.poolCoreSize = poolCoreSize;
    }

    public int getPoolMaxSize() {
        return poolMaxSize;
    }

    public void setPoolMaxSize(int poolMaxSize) {
        this.poolMaxSize = poolMaxSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getCacheCapacity() {
        return cacheCapacity;
    }

    public void setCacheCapacity(int cacheCapacity) {
        this.cacheCapacity = cacheCapacity;
    }
}
