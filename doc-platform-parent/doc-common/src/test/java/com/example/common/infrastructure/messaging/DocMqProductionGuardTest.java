package com.example.common.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 生产消息守卫：prod 必须真实 RocketMQ 且配置接入点（ENV-02）。 */
class DocMqProductionGuardTest {

    @Test
    @DisplayName("prod + mock 拒绝")
    void rejectsMockInProd() {
        DocMqProperties properties = new DocMqProperties();
        assertThatThrownBy(() -> new DocMqProductionGuard(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rocketmq");
    }

    @Test
    @DisplayName("prod + rocketmq 但缺 name-server 拒绝")
    void rejectsMissingNameServer() {
        DocMqProperties properties = new DocMqProperties();
        properties.setMode(DocMqProperties.MODE_ROCKETMQ);
        assertThatThrownBy(() -> new DocMqProductionGuard(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rocketmq.name-server");
    }

    @Test
    @DisplayName("prod + rocketmq + name-server 通过")
    void allowsConfiguredRocketMq() {
        DocMqProperties properties = new DocMqProperties();
        properties.setMode(DocMqProperties.MODE_ROCKETMQ);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("rocketmq.name-server", "mq.example.invalid:9876");
        assertThatCode(() -> new DocMqProductionGuard(properties, environment))
                .doesNotThrowAnyException();
    }
}
