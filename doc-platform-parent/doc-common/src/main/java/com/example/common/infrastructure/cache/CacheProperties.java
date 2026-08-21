package com.example.common.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 两级缓存参数（doc.cache.*，design/06 §二 TTL 清单）。
 *
 * <p>口径说明：L1(Redis)=60min±10%（design/06 §4.1）、L2(Caffeine)=5min±10%，
 * 空值占位 30s，重建锁 3s；抖动（jitter）避免缓存雪崩同步过期。
 * 阶段二自 meta-service 下沉至 doc-common：meta/runtime 共用同一套缓存骨架
 * （评审补项：runtime 侧 schema 两级缓存复用 doc-common 缓存骨架）。</p>
 */
@ConfigurationProperties(prefix = "doc.cache")
public class CacheProperties {

    /** L1 Redis TTL（分钟，±jitter 抖动；对齐 design/06 §4.1） */
    private long l1TtlMinutes = 60;

    /** L2 Caffeine TTL（分钟，±jitter 抖动） */
    private long l2TtlMinutes = 5;

    /** 穿透空值占位 TTL（秒） */
    private long nullTtlSeconds = 30;

    /** 击穿重建锁 TTL（秒） */
    private long rebuildLockTtlSeconds = 3;

    /** 未获重建锁时的等待间隔（毫秒） */
    private long rebuildWaitMillis = 50;

    /** 未获重建锁时的最大等待次数（超过则直读回源不回填） */
    private int rebuildMaxWaits = 3;

    /** TTL 抖动百分比（±10%） */
    private int jitterPercent = 10;

    /** Caffeine maximumSize */
    private long maximumSize = 1000;

    /** 启动预热开关 */
    private boolean warmupEnabled = true;

    public long getL1TtlMinutes() {
        return l1TtlMinutes;
    }

    public void setL1TtlMinutes(long l1TtlMinutes) {
        this.l1TtlMinutes = l1TtlMinutes;
    }

    public long getL2TtlMinutes() {
        return l2TtlMinutes;
    }

    public void setL2TtlMinutes(long l2TtlMinutes) {
        this.l2TtlMinutes = l2TtlMinutes;
    }

    public long getNullTtlSeconds() {
        return nullTtlSeconds;
    }

    public void setNullTtlSeconds(long nullTtlSeconds) {
        this.nullTtlSeconds = nullTtlSeconds;
    }

    public long getRebuildLockTtlSeconds() {
        return rebuildLockTtlSeconds;
    }

    public void setRebuildLockTtlSeconds(long rebuildLockTtlSeconds) {
        this.rebuildLockTtlSeconds = rebuildLockTtlSeconds;
    }

    public long getRebuildWaitMillis() {
        return rebuildWaitMillis;
    }

    public void setRebuildWaitMillis(long rebuildWaitMillis) {
        this.rebuildWaitMillis = rebuildWaitMillis;
    }

    public int getRebuildMaxWaits() {
        return rebuildMaxWaits;
    }

    public void setRebuildMaxWaits(int rebuildMaxWaits) {
        this.rebuildMaxWaits = rebuildMaxWaits;
    }

    public int getJitterPercent() {
        return jitterPercent;
    }

    public void setJitterPercent(int jitterPercent) {
        this.jitterPercent = jitterPercent;
    }

    public long getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(long maximumSize) {
        this.maximumSize = maximumSize;
    }

    public boolean isWarmupEnabled() {
        return warmupEnabled;
    }

    public void setWarmupEnabled(boolean warmupEnabled) {
        this.warmupEnabled = warmupEnabled;
    }
}
