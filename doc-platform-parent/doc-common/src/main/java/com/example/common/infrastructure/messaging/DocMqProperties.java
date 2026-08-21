package com.example.common.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQ 门面配置（任务清单 1：可切换双模式）。
 *
 * <ul>
 *   <li>{@code doc.mq.mode=mock}（缺省）：进程内 Producer/Consumer 仿真 +
 *       Redis Pub/Sub 跨实例桥接，供本地演示与集成测试；</li>
 *   <li>{@code doc.mq.mode=rocketmq}：rocketmq-spring-boot-starter 真实装配
 *       （要求 rocketmq.name-server 经环境变量注入）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "doc.mq")
public class DocMqProperties {

    /** mock 模式（缺省） */
    public static final String MODE_MOCK = "mock";

    /** rocketmq 真实模式 */
    public static final String MODE_ROCKETMQ = "rocketmq";

    /** 消息模式（mock/rocketmq，非法值按 mock 处理并 WARN） */
    private String mode = MODE_MOCK;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 是否 rocketmq 真实模式（严格匹配） */
    public boolean isRocketMq() {
        return MODE_ROCKETMQ.equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}
