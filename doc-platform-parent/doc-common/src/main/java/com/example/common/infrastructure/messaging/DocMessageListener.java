package com.example.common.infrastructure.messaging;

/**
 * 消息监听器门面（防腐；mock 模式进程内分发回调，rocketmq 模式由
 * 各服务 {@code @RocketMQMessageListener} 容器承接，本接口不参与）。
 *
 * <p>实现类注册为 Spring Bean 后由 {@link InMemoryMessageBroker} 自动收集；
 * 同一 topic 可挂多个监听器（广播语义逐一分发，集群语义 mock 下近似为
 * 本进程内逐一分发）。</p>
 */
public interface DocMessageListener {

    /** 订阅 Topic（DocMqConstants.Topics） */
    String topic();

    /** 消费 Group（DocMqConstants.Groups） */
    String consumerGroup();

    /** 期望语义模式（BROADCAST/ORDERLY/NORMAL） */
    MessageMode mode();

    /**
     * 消费一条消息（消费失败抛异常由 broker 记录；mock 模式不重投，
     * 重试语义依赖 Outbox/广播 TTL 兜底，BC-04）。
     *
     * @param message 消息
     */
    void onMessage(DocMessage message);
}
