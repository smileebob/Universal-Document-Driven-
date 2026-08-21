package com.example.common.infrastructure.messaging;

import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * RocketMQ 未配置时自动排除官方自动装配（BC-03：MQ 不可用不阻断启动）。
 *
 * <p>背景：rocketmq-spring-boot-starter 的 RocketMQAutoConfiguration 在
 * {@code rocketmq.name-server} 为空时创建 DefaultMQProducer 直接抛异常，
 * 导致 dev/仿真环境（无 RocketMQ 接入点）启动失败。本处理器在环境装配后检查：
 * name-server 未配置则将 RocketMQAutoConfiguration 追加进
 * {@code spring.autoconfigure.exclude}；业务侧失效广播/Outbox 组件已有
 * 条件装配 + NoOp 兜底，不受影响。未引入 rocketmq starter 的模块
 * （如 doc-gateway）排除一个不存在的自动装配类亦无副作用。</p>
 *
 * <p>生产口径：ROCKETMQ_ENDPOINT 经环境变量注入后 name-server 非空，
 * 不追加排除，官方自动装配原样生效。仅做字符串判断，不引用 rocketmq 类。</p>
 */
public class RocketMqOptionalPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RocketMqOptionalPostProcessor.class);

    private static final String NAME_SERVER_KEY = "rocketmq.name-server";
    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";
    private static final String ROCKETMQ_AUTOCONFIG =
            "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String nameServer = environment.getProperty(NAME_SERVER_KEY);
        if (nameServer != null && !nameServer.isBlank()) {
            return;
        }
        String existing = environment.getProperty(EXCLUDE_KEY, "");
        String value = existing.isBlank()
                ? ROCKETMQ_AUTOCONFIG
                : existing + "," + ROCKETMQ_AUTOCONFIG;
        environment.getPropertySources().addFirst(new MapPropertySource(
                "rocketmqOptionalExclude",
                Collections.singletonMap(EXCLUDE_KEY, value)));
        log.warn("[rocketmq] {} 未配置，排除 RocketMQAutoConfiguration 启动降级（BC-03："
                + "失效广播/Outbox 降级 NoOp，MQ 接入点经环境变量注入后自动恢复装配）", NAME_SERVER_KEY);
    }
}
