package com.example.common.infrastructure.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * mock 模式跨实例桥接（Redis Pub/Sub 承载，本地仿真环境的「传输层」）。
 *
 * <p>进程内分发只覆盖本实例监听器；为在本地多进程部署（gateway/meta/runtime
 * 分离）下近似真实广播/FIFO 的跨实例送达，发布时同时经 Redis 频道
 * {@value #CHANNEL} 扇出，其他实例收到后落 {@link InMemoryMessageBroker#dispatchLocal}。
 * 信封携带 origin 实例 ID，自发消息不回环（本实例已在发布路径本地分发）。</p>
 *
 * <p>Redis 不可达时全部桥接操作降级为 WARN，mock 退化为纯进程内仿真
 * （BC-03 兜底思想：传输缺失不阻断业务）。</p>
 */
public class MockMqRedisBridge {

    /** 桥接频道（非业务 Topic，仅 mock 模式传输用） */
    public static final String CHANNEL = "ddp:mq:mock:bridge";

    private static final Logger log = LoggerFactory.getLogger(MockMqRedisBridge.class);

    private final StringRedisTemplate redisTemplate;
    private final InMemoryMessageBroker broker;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String instanceId = UUID.randomUUID().toString();

    public MockMqRedisBridge(StringRedisTemplate redisTemplate, InMemoryMessageBroker broker,
                             RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = redisTemplate;
        this.broker = broker;
        if (redisTemplate == null) {
            log.warn("[mock-mq] Redis bridge construct degraded (template missing), cross-instance invalidation relies on TTL fallback (BC-03)");
        } else if (listenerContainer != null) {
            try {
                listenerContainer.addMessageListener(this::onRedisMessage, new ChannelTopic(CHANNEL));
                log.info("[mock-mq] Redis 桥接订阅就绪: channel={}, instanceId={}", CHANNEL, instanceId);
            } catch (RuntimeException e) {
                log.warn("[mock-mq] Redis 桥接订阅失败（退化为纯进程内仿真）: {}", e.getMessage());
            }
        }
    }

    /** 跨实例扇出（自发消息以 origin 标记防回环） */
    public void publish(DocMessage message) {
        if (redisTemplate == null) {
            log.warn("[mock-mq] Redis bridge not ready (template missing), message stays in-process only");
            return;
        }
        try {
            Envelope envelope = new Envelope();
            envelope.setOrigin(instanceId);
            envelope.setMessage(message);
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[mock-mq] Redis 桥接发布失败（仅本进程内生效）: {}", e.getMessage());
        }
    }

    /** Redis 频道回调：非自发消息落本地分发 */
    private void onRedisMessage(Message redisMessage, byte[] pattern) {
        try {
            Envelope envelope = objectMapper.readValue(redisMessage.getBody(), Envelope.class);
            if (envelope == null || envelope.getMessage() == null || instanceId.equals(envelope.getOrigin())) {
                return;
            }
            broker.dispatchLocal(envelope.getMessage());
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("[mock-mq] Redis 桥接消息解析失败: {}", e.getMessage());
        }
    }

    /** 桥接信封（origin + 门面消息） */
    public static class Envelope {

        private String origin;
        private DocMessage message;

        public String getOrigin() {
            return origin;
        }

        public void setOrigin(String origin) {
            this.origin = origin;
        }

        public DocMessage getMessage() {
            return message;
        }

        public void setMessage(DocMessage message) {
            this.message = message;
        }
    }
}
