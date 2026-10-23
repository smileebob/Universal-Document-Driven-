package com.example.common.infrastructure.messaging;

import org.springframework.core.env.Environment;

/**
 * 生产消息模式守卫（ENV-02）：prod profile 下必须使用真实 RocketMQ，
 * 禁止 mock 进程内仿真，且必须配置 rocketmq.name-server；
 * 任何配置缺失都启动失败，禁止静默降级（避免生产“看起来在跑”但事件未真正投递）。
 */
public final class DocMqProductionGuard {

    static final String NAME_SERVER_KEY = "rocketmq.name-server";

    public DocMqProductionGuard(DocMqProperties properties, Environment environment) {
        if (!properties.isRocketMq()) {
            throw new IllegalStateException(
                    "[mq] 启动拒绝：prod profile 要求 DOC_MQ_MODE=rocketmq，当前 mode="
                            + properties.getMode() + "（禁止生产使用 mock 仿真）");
        }
        String nameServer = environment.getProperty(NAME_SERVER_KEY);
        if (nameServer == null || nameServer.isBlank()) {
            throw new IllegalStateException(
                    "[mq] 启动拒绝：prod profile 未配置 " + NAME_SERVER_KEY
                            + "（请经 ROCKETMQ_ENDPOINT 环境变量注入真实接入点）");
        }
    }
}
