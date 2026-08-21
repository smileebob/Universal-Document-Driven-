package com.example.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trace_id 入口过滤器测试（任务 #20 断点补齐，design/11 §4.1）：
 * 透传网关头 / 缺失兜底生成 / MDC 写入与清理 / 响应头回写。
 */
class TraceIdServletFilterTest {

    private final TraceIdServletFilter filter = new TraceIdServletFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("携带 X-Trace-Id：MDC 透传 + 响应头回写 + 请求后清理")
    void headerPresent_propagatedAndCleaned() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdServletFilter.TRACE_ID_HEADER, "trace-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
                seenInChain[0] = MDC.get(TraceIdServletFilter.MDC_TRACE_ID));

        assertThat(seenInChain[0]).isEqualTo("trace-abc-123");
        assertThat(response.getHeader(TraceIdServletFilter.TRACE_ID_HEADER))
                .isEqualTo("trace-abc-123");
        assertThat(MDC.get(TraceIdServletFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    @DisplayName("无 X-Trace-Id：兜底生成 32 位 traceId（直连服务不断链）")
    void headerMissing_generated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
                seenInChain[0] = MDC.get(TraceIdServletFilter.MDC_TRACE_ID));

        assertThat(seenInChain[0]).isNotBlank().hasSize(32);
        assertThat(response.getHeader(TraceIdServletFilter.TRACE_ID_HEADER))
                .isEqualTo(seenInChain[0]);
        assertThat(MDC.get(TraceIdServletFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    @DisplayName("链路异常：finally 仍清理 MDC 防线程复用串链")
    void chainThrows_mdcStillCleaned() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdServletFilter.TRACE_ID_HEADER, "trace-err");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, response, (req, res) -> {
                    throw new IllegalStateException("boom");
                }));

        assertThat(MDC.get(TraceIdServletFilter.MDC_TRACE_ID)).isNull();
    }
}
