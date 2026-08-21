package com.example.common.infrastructure.messaging;

/**
 * 消息发布门面（防腐，双模式共用；design/07 唯一发送入口）。
 *
 * <p>实现：</p>
 * <ul>
 *   <li>{@code MockDocMessagePublisher}：doc.mq.mode=mock，进程内分发 +
 *       可选 Redis Pub/Sub 跨实例桥接（本地仿真/演示/集成测试）；</li>
 *   <li>{@code RocketMqDocMessagePublisher}：doc.mq.mode=rocketmq，
 *       rocketmq-spring-boot-starter 真实投递（生产）。</li>
 * </ul>
 *
 * <p>语义：尽力而为同步发送，失败返回 false（调用方决定降级/重投，
 * 如 Outbox 中继的退避扫描兜底），不抛业务异常。</p>
 */
public interface DocMessagePublisher {

    /**
     * 同步发送一条消息。
     *
     * @param message 门面消息模型
     * @return true=发送成功（mock 模式=分发无异常）；false=失败
     */
    boolean publish(DocMessage message);

    /** 当前 MQ 模式（mock/rocketmq，日志与诊断用） */
    String mode();
}
