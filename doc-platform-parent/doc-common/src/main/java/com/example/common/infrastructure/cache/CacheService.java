package com.example.common.infrastructure.cache;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * 两级缓存服务（design/06 §5：L2 Caffeine → L1 Redis → 回源）。
 *
 * <p>阶段二自 meta-service 下沉至 doc-common（评审补项）：meta-service 与
 * runtime-service 共用本骨架。经 {@link CacheAutoConfiguration} 条件装配
 * （classpath 存在 StringRedisTemplate 时才生效）。</p>
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>L2 Caffeine：maximumSize=1000，expireAfterWrite 5min±10%（逐条抖动，防同步过期雪崩）；</li>
 *   <li>L1 Redis：StringRedisTemplate，TTL 60min±10%（design/06 §4.1）；穿透空值占位 {@link #NULL_PLACEHOLDER} 30s；</li>
 *   <li>击穿重建锁：{@code SETNX}（值=UUID owner token）+ TTL 3s，获锁者回源回填，
 *       释放经 Lua 比对 token 才 DEL（防误删他人锁）；回填前比对失效时间戳防旧值回填；
 *       未获锁者等待 50ms 轮询重读，仍 miss 则直读回源不回填（design/06 §5.1）；</li>
 *   <li>Redis 不可达降级：全部 Redis 操作 try/catch 兜底，自动退化为 Caffeine + 直读回源，
 *       不阻塞启动、不抛业务异常；</li>
 *   <li>写失效：{@link #evict} 先删本地再 DEL Redis（重试 3 次），配合 MQ 广播跨实例失效。</li>
 * </ul>
 */
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    /** 穿透空值占位串（TTL 30s） */
    public static final String NULL_PLACEHOLDER = "NULL";

    /** Redis DEL 失效重试次数（design/06 §5.2） */
    private static final int EVICT_RETRY_TIMES = 3;

    /**
     * 重建锁释放 Lua（评审修复 Minor-10）：仅当锁值 == 调用方 owner token 才 DEL，
     * 防止锁 TTL 过期后误删其他线程新持有的锁。
     */
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

    private final CacheProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, String> localCache;

    /** 失效时间戳（nanoTime）：重建回填前比对，防止回源期间被失效的 key 回填旧值 */
    private final ConcurrentMap<String, Long> evictStamps = new ConcurrentHashMap<>();

    /** L1/L2 命中计数（bindMetrics 后生效；未绑定为 null 不打点，design/11 §4.2 缓存命中率） */
    private Counter l2HitCounter;
    private Counter l2MissCounter;
    private Counter l1HitCounter;
    private Counter l1MissCounter;

    public CacheService(CacheProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaximumSize())
                .expireAfter(new JitteredExpiry(properties.getL2TtlMinutes(), properties.getJitterPercent()))
                .build();
    }

    /**
     * 两级读取：L2 → L1（命中回填 L2）。
     *
     * @param key 缓存键
     * @return 值（可能为 {@link #NULL_PLACEHOLDER} 空值占位）；两级均 miss 返回 null
     */
    public String get(String key) {
        String local = localCache.getIfPresent(key);
        if (local != null) {
            increment(l2HitCounter);
            return local;
        }
        increment(l2MissCounter);
        String remote = redisGet(key);
        if (remote != null) {
            increment(l1HitCounter);
            localCache.put(key, remote);
        } else {
            increment(l1MissCounter);
        }
        return remote;
    }

    /**
     * 绑定缓存命中率计数器（{@code ddp_cache_get_total{layer=l2|l1,result=hit|miss}}，
     * design/11 §4.2 / 任务 #20）。经 CacheAutoConfiguration 在容器存在
     * MeterRegistry 时调用；未绑定则不打点（测试与无 actuator 场景兼容）。
     *
     * @param registry Micrometer 注册表
     */
    public void bindMetrics(MeterRegistry registry) {
        this.l2HitCounter = Counter.builder("ddp_cache_get_total")
                .tag("layer", "l2").tag("result", "hit").register(registry);
        this.l2MissCounter = Counter.builder("ddp_cache_get_total")
                .tag("layer", "l2").tag("result", "miss").register(registry);
        this.l1HitCounter = Counter.builder("ddp_cache_get_total")
                .tag("layer", "l1").tag("result", "hit").register(registry);
        this.l1MissCounter = Counter.builder("ddp_cache_get_total")
                .tag("layer", "l1").tag("result", "miss").register(registry);
    }

    /** 计数器空安全自增（未绑定 metrics 时 NOOP） */
    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 两级写入（正常值）：L1 TTL 60min±10%（design/06 §4.1）。
     *
     * @param key   缓存键
     * @param value JSON 值（不可为 null，空语义用 {@link #putNull}）
     */
    public void put(String key, String value) {
        localCache.put(key, value);
        redisSet(key, value, jitterSeconds(properties.getL1TtlMinutes() * 60));
    }

    /**
     * 穿透防护：写入空值占位（TTL 30s）。
     *
     * @param key 缓存键
     */
    public void putNull(String key) {
        localCache.put(key, NULL_PLACEHOLDER);
        redisSet(key, NULL_PLACEHOLDER, properties.getNullTtlSeconds());
    }

    /**
     * 写失效：先删 L2，再 DEL L1（重试 {@value #EVICT_RETRY_TIMES} 次）。
     *
     * @param key 缓存键
     */
    public void evict(String key) {
        evictStamps.put(key, System.nanoTime());
        localCache.invalidate(key);
        for (int attempt = 1; attempt <= EVICT_RETRY_TIMES; attempt++) {
            try {
                redisTemplate.delete(key);
                return;
            } catch (RuntimeException e) {
                log.warn("[cache] Redis DEL 失败（{}/{}）: key={}, cause={}",
                        attempt, EVICT_RETRY_TIMES, key, e.getMessage());
            }
        }
        log.warn("[cache] Redis DEL 重试耗尽，依赖 TTL 兜底过期: key={}", key);
    }

    /**
     * 尝试获取击穿重建锁（SETNX + TTL 3s，值=UUID owner token）。
     *
     * @param lockKey 锁键（{@link CacheKeyBuilder#rebuildLock}）
     * @return 获锁成功返回 owner token（释放锁时回传）；未获锁返回 null；
     *         Redis 不可达时返回非 null token（退化为直读重建）
     */
    public String tryRebuildLock(String lockKey) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, token, Duration.ofSeconds(properties.getRebuildLockTtlSeconds()));
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (RuntimeException e) {
            log.warn("[cache] 重建锁获取异常（Redis 降级，直读重建）: lockKey={}, cause={}",
                    lockKey, e.getMessage());
            return token;
        }
    }

    /**
     * 释放击穿重建锁（Lua：GET==token 才 DEL，finally 调用，失败仅 WARN 依赖锁 TTL 自然过期）。
     *
     * @param lockKey 锁键
     * @param token   {@link #tryRebuildLock} 返回的 owner token（null 时不操作）
     */
    public void releaseRebuildLock(String lockKey, String token) {
        if (token == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("[cache] 重建锁释放失败（依赖 TTL 过期）: lockKey={}, cause={}", lockKey, e.getMessage());
        }
    }

    /**
     * 重建回填（评审修复 Minor-10）：写 Redis 前比对 key 是否在重建开始后已被
     * {@link #evict} 失效；已失效则放弃回填（防止旧值覆盖新值），否则正常两级写入。
     *
     * @param key               缓存键
     * @param value             JSON 值
     * @param rebuildStartStamp 重建开始时间戳（{@link System#nanoTime()}）
     * @return true=已回填；false=重建期间被失效，放弃回填
     */
    public boolean putIfNotEvicted(String key, String value, long rebuildStartStamp) {
        Long evictedAt = evictStamps.get(key);
        if (evictedAt != null && evictedAt > rebuildStartStamp) {
            log.warn("[cache] 重建期间 key 已被失效，放弃回填旧值: key={}", key);
            return false;
        }
        put(key, value);
        return true;
    }

    /**
     * 未获重建锁时的等待间隔（毫秒，design/06 §5.1：50ms 轮询）。
     *
     * @return 等待毫秒数
     */
    public long rebuildWaitMillis() {
        return properties.getRebuildWaitMillis();
    }

    /**
     * 未获重建锁时的最大等待次数。
     *
     * @return 次数
     */
    public int rebuildMaxWaits() {
        return properties.getRebuildMaxWaits();
    }

    /** L2 本地缓存直读（预热命中校验 / 测试断言用） */
    public String getLocalIfPresent(String key) {
        return localCache.getIfPresent(key);
    }

    // ==================== Redis 降级封装 ====================

    private String redisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("[cache] Redis GET 降级（直读回源）: key={}, cause={}", key, e.getMessage());
            return null;
        }
    }

    private void redisSet(String key, String value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("[cache] Redis SET 降级（仅 L2 生效）: key={}, cause={}", key, e.getMessage());
        }
    }

    /** ±jitterPercent 抖动后的秒数（下限 1s） */
    private long jitterSeconds(long baseSeconds) {
        int jitterPercent = properties.getJitterPercent();
        if (jitterPercent <= 0) {
            return Math.max(1L, baseSeconds);
        }
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterPercent / 100.0;
        return Math.max(1L, Math.round(baseSeconds * factor));
    }

    /** Caffeine 逐条 TTL 抖动（expireAfterWrite 5min±10%） */
    private static final class JitteredExpiry implements Expiry<String, String> {

        private final long baseNanos;
        private final int jitterPercent;

        private JitteredExpiry(long ttlMinutes, int jitterPercent) {
            this.baseNanos = TimeUnit.MINUTES.toNanos(ttlMinutes);
            this.jitterPercent = jitterPercent;
        }

        private long jittered() {
            double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterPercent / 100.0;
            return Math.max(1L, Math.round(baseNanos * factor));
        }

        @Override
        public long expireAfterCreate(String key, String value, long currentTime) {
            return jittered();
        }

        @Override
        public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
            return jittered();
        }

        @Override
        public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
