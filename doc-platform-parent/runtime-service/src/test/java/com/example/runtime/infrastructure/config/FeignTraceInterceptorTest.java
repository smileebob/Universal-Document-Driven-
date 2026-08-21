package com.example.runtime.infrastructure.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import feign.RequestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign trace_id 透传拦截器测试（任务 #20 断点补齐，design/11 §4.1）。
 */
class FeignTraceInterceptorTest {

    private final FeignTraceInterceptor interceptor = new FeignTraceInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 有 traceId：注入 X-Trace-Id 头")
    void mdcPresent_headerInjected() {
        MDC.put(FeignTraceInterceptor.MDC_TRACE_ID, "trace-feign-1");

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertThat(template.headers().get(FeignTraceInterceptor.TRACE_ID_HEADER))
                .containsExactly("trace-feign-1");
    }

    @Test
    @DisplayName("MDC 无 traceId：不注入头（异步直发由下游入口兜底生成）")
    void mdcAbsent_noHeader() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertThat(template.headers().containsKey(FeignTraceInterceptor.TRACE_ID_HEADER))
                .isFalse();
    }
}
