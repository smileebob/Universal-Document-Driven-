package com.example.runtime.service.event;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 事件消费去重器（EV-02：event_id Redis SETNX 24h）。
 *
 * <p>Redis 不可用时降级为进程内集合（有界 LRU 近似，单实例语义；
 * 跨实例去重能力降级但消费不阻断）。TTL 24h 覆盖退避重投窗口。</p>
 */
@Component
public class EventDeduplicator {

    /** 去重键前缀（design/07 §四：ddp:runtime:idem:event:{event_id}） */
    static final String KEY_PREFIX = "ddp:runtime:idem:event:";

    /** 去重键 TTL（24h） */
    static final Duration TTL = Duration.ofHours(24);

    /** 降级集合容量上限 */
    private static final int LOCAL_LIMIT = 10_000;

    private static final Logger log = LoggerFactory.getLogger(EventDeduplicator.class);

    private final StringRedisTemplate redisTemplate;

    /** Redis 降级时的进程内集合（LinkedHashMap LRU 近似） */
    private final Map<String, Boolean> localSeen = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {

                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > LOCAL_LIMIT;
                }
            });

    public EventDeduplicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试登记事件 ID（SETNX 语义）。
     *
     * @param eventId 事件唯一 ID
     * @return true=首次消费；false=重复（24h 内已消费）
     */
    public boolean tryFirstConsume(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + eventId, "1", TTL);
            return Boolean.TRUE.equals(first);
        } catch (RuntimeException e) {
            // Redis 不可用：降级进程内去重（单实例语义）
            log.warn("[event-dedupe] Redis 不可用，降级进程内去重: cause={}", e.getMessage());
            return localSeen.putIfAbsent(eventId, Boolean.TRUE) == null;
        }
    }

    /** 降级集合当前规模（监控/测试用） */
    int localSize() {
        return localSeen.size();
    }

    /** 供测试清空降级集合 */
    void clearLocal() {
        localSeen.clear();
    }
}
