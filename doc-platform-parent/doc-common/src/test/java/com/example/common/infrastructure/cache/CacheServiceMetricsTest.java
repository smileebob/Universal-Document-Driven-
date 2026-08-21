package com.example.common.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两级缓存命中率指标测试（任务 #20，design/11 §4.2：
 * {@code ddp_cache_get_total{layer=l2|l1,result=hit|miss}}）。
 */
class CacheServiceMetricsTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private CacheService cache;
    private SimpleMeterRegistry registry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        cache = new CacheService(new CacheProperties(), redis);
        registry = new SimpleMeterRegistry();
        cache.bindMetrics(registry);
    }

    private double count(String layer, String result) {
        return registry.get("ddp_cache_get_total")
                .tags("layer", layer, "result", result).counter().count();
    }

    @Test
    @DisplayName("两级 miss：l2 miss+1、l1 miss+1")
    void bothMiss_countsMiss() {
        when(ops.get("k1")).thenReturn(null);

        assertThat(cache.get("k1")).isNull();

        assertThat(count("l2", "miss")).isEqualTo(1.0);
        assertThat(count("l1", "miss")).isEqualTo(1.0);
        assertThat(count("l2", "hit")).isZero();
    }

    @Test
    @DisplayName("L1 命中回填 L2：首次 l1 hit，二次 l2 hit")
    void l1Hit_backfillThenL2Hit() {
        when(ops.get("k2")).thenReturn("v2");

        assertThat(cache.get("k2")).isEqualTo("v2");
        assertThat(count("l2", "miss")).isEqualTo(1.0);
        assertThat(count("l1", "hit")).isEqualTo(1.0);

        assertThat(cache.get("k2")).isEqualTo("v2");
        assertThat(count("l2", "hit")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("未绑定 metrics：get 正常降级不打点（null-safe）")
    void unbound_noMetricsNoError() {
        CacheService unbound = new CacheService(new CacheProperties(), redis);
        when(ops.get(anyString())).thenReturn(null);
        assertThat(unbound.get("k3")).isNull();
    }

    @Test
    @DisplayName("put 写两级：后续 get 命中 L2（写入路径不产生命中率计数）")
    void put_thenL2Hit() {
        cache.put("k4", "v4");

        assertThat(cache.get("k4")).isEqualTo("v4");
        assertThat(count("l2", "hit")).isEqualTo(1.0);
        org.mockito.Mockito.verify(ops).set(eq("k4"), eq("v4"), anyLong(),
                eq(java.util.concurrent.TimeUnit.SECONDS));
    }
}
