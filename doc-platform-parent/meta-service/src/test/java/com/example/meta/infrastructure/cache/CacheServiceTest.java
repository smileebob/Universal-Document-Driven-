package com.example.meta.infrastructure.cache;

import com.example.common.infrastructure.cache.CacheProperties;
import com.example.common.infrastructure.cache.CacheService;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheService 两级缓存测试（Redis 侧 Mockito 仿真）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new CacheService(new CacheProperties(), redisTemplate);
    }

    @Test
    @DisplayName("put 双写：L2 命中后不再访问 Redis")
    void put_then_localHit() {
        cacheService.put("k1", "v1");
        verify(valueOperations).set(eq("k1"), eq("v1"), anyLong(), eq(TimeUnit.SECONDS));

        assertThat(cacheService.get("k1")).isEqualTo("v1");
        // L2 已命中，Redis GET 不应发生
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("get：L2 miss 走 Redis 并回填 L2")
    void get_redisHit_backfillLocal() {
        when(valueOperations.get("k2")).thenReturn("v2");
        assertThat(cacheService.get("k2")).isEqualTo("v2");
        assertThat(cacheService.getLocalIfPresent("k2")).isEqualTo("v2");

        // 第二次命中 L2
        assertThat(cacheService.get("k2")).isEqualTo("v2");
        verify(valueOperations, times(1)).get("k2");
    }

    @Test
    @DisplayName("putNull：空值占位（L2 可见 NULL 占位）")
    void putNull_placeholder() {
        cacheService.putNull("k3");
        assertThat(cacheService.getLocalIfPresent("k3")).isEqualTo(CacheService.NULL_PLACEHOLDER);
        verify(valueOperations).set(eq("k3"), eq(CacheService.NULL_PLACEHOLDER), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("evict：Redis DEL 失败重试 3 次后放弃（不抛异常）")
    void evict_retry_then_giveUp() {
        cacheService.put("k4", "v4");
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("k4");
        cacheService.evict("k4");
        verify(redisTemplate, times(3)).delete("k4");
        assertThat(cacheService.getLocalIfPresent("k4")).isNull();
    }

    @Test
    @DisplayName("tryRebuildLock：SETNX 值=UUID token；获锁返回 token/未获锁 null；Redis 异常退化非 null")
    void tryRebuildLock_branches() {
        when(valueOperations.setIfAbsent(eq("lock1"), anyString(), any(Duration.class)))
                .thenReturn(true);
        String token = cacheService.tryRebuildLock("lock1");
        assertThat(token).isNotNull();

        when(valueOperations.setIfAbsent(eq("lock2"), anyString(), any(Duration.class)))
                .thenReturn(false);
        assertThat(cacheService.tryRebuildLock("lock2")).isNull();

        when(valueOperations.setIfAbsent(eq("lock3"), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertThat(cacheService.tryRebuildLock("lock3")).isNotNull();
    }

    @Test
    @DisplayName("releaseRebuildLock：经 Lua 脚本携带 token 释放；异常仅 WARN 不抛出")
    void releaseRebuildLock_luaRelease() {
        cacheService.releaseRebuildLock("lock4", "token-1");
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("lock4")), eq("token-1"));

        doThrow(new RuntimeException("redis down")).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any());
        cacheService.releaseRebuildLock("lock4", "token-1");

        // null token（未获锁）不执行任何释放
        cacheService.releaseRebuildLock("lock4", null);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("putIfNotEvicted：重建期间被 evict 则放弃回填；未失效正常写入")
    void putIfNotEvicted_staleGuard() {
        long stampBefore = System.nanoTime();
        cacheService.evict("k7");
        assertThat(cacheService.putIfNotEvicted("k7", "stale", stampBefore)).isFalse();
        assertThat(cacheService.getLocalIfPresent("k7")).isNull();

        long stampAfter = System.nanoTime();
        assertThat(cacheService.putIfNotEvicted("k7", "fresh", stampAfter)).isTrue();
        assertThat(cacheService.getLocalIfPresent("k7")).isEqualTo("fresh");
    }

    @Test
    @DisplayName("Redis GET 异常降级：返回 null（直读回源路径）")
    void get_redisDown_degrade() {
        when(valueOperations.get("k5")).thenThrow(new RuntimeException("redis down"));
        assertThat(cacheService.get("k5")).isNull();
    }

    @Test
    @DisplayName("Redis SET 异常降级：L2 仍生效")
    void put_redisDown_localStillWorks() {
        doThrow(new RuntimeException("redis down")).when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        cacheService.put("k6", "v6");
        assertThat(cacheService.getLocalIfPresent("k6")).isEqualTo("v6");
    }

    @Test
    @DisplayName("本地栅栏：失效后旧回源快照不得回填，新快照可回填")
    void localFence_rejectsStaleRebuild() {
        CacheService.CacheStamp stale = cacheService.beginRebuild("k-local", false);
        cacheService.invalidateLocal("k-local", 1L);
        assertThat(cacheService.finishRebuild("k-local", "old", stale, false)).isFalse();
        assertThat(cacheService.getLocalIfPresent("k-local")).isNull();

        CacheService.CacheStamp fresh = cacheService.beginRebuild("k-local", false);
        assertThat(cacheService.finishRebuild("k-local", "new", fresh, false)).isTrue();
        assertThat(cacheService.getLocalIfPresent("k-local")).isEqualTo("new");
    }

    @Test
    @DisplayName("等待参数与属性默认值")
    void waitParams() {
        assertThat(cacheService.rebuildWaitMillis()).isEqualTo(50L);
        assertThat(cacheService.rebuildMaxWaits()).isEqualTo(3);
        CacheProperties properties = new CacheProperties();
        assertThat(properties.isWarmupEnabled()).isTrue();
        assertThat(properties.getMaximumSize()).isEqualTo(1000);
        assertThat(properties.getL1TtlMinutes()).isEqualTo(60);
        assertThat(properties.getL2TtlMinutes()).isEqualTo(5);
        assertThat(properties.getJitterPercent()).isEqualTo(10);
    }
}
