package com.example.meta.domain.messaging;

/**
 * 配置缓存失效广播发布端口（domain 层端口，实现在 infrastructure/messaging）。
 *
 * <p>端口置于 domain 层的原因：ArchUnit 分层中 Service 层仅可被 Controller 访问，
 * infrastructure 实现 domain 端口是唯一的反向注入路径。</p>
 *
 * <p>实现语义约定（BC-03 可容忍丢失）：发布失败只 WARN 不阻断业务写，
 * 由 Caffeine TTL≤5min 兜底最终一致（design/06 §5.3）。RocketMQ 未配置时
 * 装配 NoOp 实现，启动不受影响。</p>
 */
public interface SchemaInvalidationPublisher {

    /**
     * 发布失效广播（尽力而为，失败不抛业务异常）。
     *
     * @param event 自包含失效事件
     */
    void publish(SchemaInvalidationEvent event);
}
