package com.example.meta.infrastructure.messaging;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失效广播装配组件测试（条件类 / 发布器 / 消费者 / NoOp / Configuration）。
 */
class MessagingComponentsTest {

    private ConditionOutcome outcome(String mode, String nameServer, String enabled) {
        MockEnvironment env = new MockEnvironment();
        if (mode != null) {
            env.setProperty("doc.mq.mode", mode);
        }
        if (nameServer != null) {
            env.setProperty("rocketmq.name-server", nameServer);
        }
        if (enabled != null) {
            env.setProperty("doc.mq.schema-invalidation.enabled", enabled);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);
        return new RocketMqAvailableCondition().getMatchOutcome(context, null);
    }

    @Test
    @DisplayName("条件类：doc.mq.mode=rocketmq 且 name-server 已配置且开关开启 → match")
    void condition_match() {
        assertThat(outcome("rocketmq", "1.2.3.4:8080", null).isMatch()).isTrue();
        assertThat(outcome("rocketmq", "1.2.3.4:8080", "true").isMatch()).isTrue();
    }

    @Test
    @DisplayName("条件类：缺省 mock 模式/空白 name-server/开关 false → noMatch")
    void condition_noMatch() {
        // 缺省 mode（mock）：即便 name-server 配置也不装配真实 RocketMQ
        assertThat(outcome(null, null, null).isMatch()).isFalse();
        assertThat(outcome("mock", "1.2.3.4:8080", null).isMatch()).isFalse();
        // rocketmq 模式但 name-server 空白
        assertThat(outcome("rocketmq", null, null).isMatch()).isFalse();
        assertThat(outcome("rocketmq", "  ", null).isMatch()).isFalse();
        // rocketmq 模式但失效广播开关关闭
        assertThat(outcome("rocketmq", "1.2.3.4:8080", "false").isMatch()).isFalse();
    }

    @Test
    @DisplayName("RocketMQ 发布器：destination=topic:tag，消息体含 event_id")
    void rocketMqPublisher_sends() {
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        SchemaInvalidationPublisher publisher =
                new RocketMqSchemaInvalidationPublisher(rocketMQTemplate);
        publisher.publish(SchemaInvalidationEvent.published("entity_schema", "customer", 2L));

        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rocketMQTemplate).syncSend(destination.capture(), payload.capture());
        assertThat(destination.getValue()).isEqualTo("schema-invalidation:entity_schema");
        assertThat(payload.getValue().toString()).contains("event_id");
    }

    @Test
    @DisplayName("BC-03：发送失败仅 WARN 不阻断业务")
    void rocketMqPublisher_sendFailure_noThrow() {
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        doThrow(new RuntimeException("mq down")).when(rocketMQTemplate)
                .syncSend(anyString(), (Object) anyString());
        SchemaInvalidationPublisher publisher =
                new RocketMqSchemaInvalidationPublisher(rocketMQTemplate);
        assertThatCode(() -> publisher.publish(
                SchemaInvalidationEvent.published("entity_schema", "customer", 2L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOp 发布器：publish 无副作用不抛异常")
    void noOpPublisher() {
        NoOpSchemaInvalidationPublisher publisher = new NoOpSchemaInvalidationPublisher();
        assertThatCode(() -> {
            publisher.publish(SchemaInvalidationEvent.published("entity_schema", "a", 1L));
            publisher.publish(SchemaInvalidationEvent.disabled("entity_schema", "a", 2L));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BROADCASTING 消费者：委托 handler；handler 异常不上抛")
    void broadcastListener_delegates() {
        SchemaInvalidationHandler handler = mock(SchemaInvalidationHandler.class);
        SchemaInvalidationBroadcastListener listener =
                new SchemaInvalidationBroadcastListener(handler);
        listener.onMessage("msg-1");
        verify(handler).handle(eq("msg-1"));

        doThrow(new RuntimeException("boom")).when(handler).handle(anyString());
        assertThatCode(() -> listener.onMessage("msg-2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Configuration 工厂方法产出对应实例")
    void configuration_beans() {
        SchemaInvalidationConfiguration configuration = new SchemaInvalidationConfiguration();
        assertThat(configuration.rocketMqSchemaInvalidationPublisher(mock(RocketMQTemplate.class)))
                .isInstanceOf(RocketMqSchemaInvalidationPublisher.class);
        assertThat(configuration.schemaInvalidationBroadcastListener(mock(SchemaInvalidationHandler.class)))
                .isInstanceOf(SchemaInvalidationBroadcastListener.class);
        assertThat(configuration.noOpSchemaInvalidationPublisher())
                .isInstanceOf(NoOpSchemaInvalidationPublisher.class);
    }
}
