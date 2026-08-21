package com.example.common.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * RocketMqOptionalPostProcessor 单测（BC-03 启动降级口径）。
 */
class RocketMqOptionalPostProcessorTest {

    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";
    private static final String ROCKETMQ_AUTOCONFIG =
            "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration";

    private final RocketMqOptionalPostProcessor processor = new RocketMqOptionalPostProcessor();

    private StandardEnvironment envWith(String nameServer, String existingExclude) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> props = new java.util.HashMap<>();
        if (nameServer != null) {
            props.put("rocketmq.name-server", nameServer);
        }
        if (existingExclude != null) {
            props.put(EXCLUDE_KEY, existingExclude);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));
        return environment;
    }

    @Test
    @DisplayName("name-server 未配置：追加排除 RocketMQAutoConfiguration")
    void nameServerAbsent_excluded() {
        StandardEnvironment environment = envWith("", null);
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty(EXCLUDE_KEY)).isEqualTo(ROCKETMQ_AUTOCONFIG);
    }

    @Test
    @DisplayName("name-server 未配置且已有其它排除项：追加而非覆盖")
    void nameServerAbsent_appendsToExisting() {
        StandardEnvironment environment = envWith(null, "com.foo.BarAutoConfiguration");
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty(EXCLUDE_KEY))
                .isEqualTo("com.foo.BarAutoConfiguration," + ROCKETMQ_AUTOCONFIG);
    }

    @Test
    @DisplayName("name-server 已配置：不追加排除（生产装配不受影响）")
    void nameServerConfigured_notExcluded() {
        StandardEnvironment environment = envWith("rmq.example:8848", null);
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.containsProperty(EXCLUDE_KEY)).isFalse();
    }
}
