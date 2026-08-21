package com.example.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * TraceIdResponseNormalizeFilter 单元测试（任务 #20：响应头重复断点修复）。
 *
 * <p>覆盖矩阵：重复值归一化为单值；单值/缺失不动；order 紧邻
 * NettyWriteResponseFilter 之上（响应阶段先于响应写出执行）。</p>
 */
class TraceIdResponseNormalizeFilterTest {

    private final TraceIdResponseNormalizeFilter filter = new TraceIdResponseNormalizeFilter();

    private MockServerWebExchange doFilter(String... presetValues) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/data/x").build());
        for (String value : presetValues) {
            exchange.getResponse().getHeaders().add(TraceIdGlobalFilter.TRACE_ID_HEADER, value);
        }
        GatewayFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    @DisplayName("重复 X-Trace-Id 响应头：归一化为单值")
    void dedupeWhenDuplicated() {
        MockServerWebExchange exchange = doFilter("trace-001", "trace-001");
        assertThat(exchange.getResponse().getHeaders().get(TraceIdGlobalFilter.TRACE_ID_HEADER))
                .containsExactly("trace-001");
    }

    @Test
    @DisplayName("单值 X-Trace-Id：保持不变")
    void keepSingleValue() {
        MockServerWebExchange exchange = doFilter("trace-002");
        assertThat(exchange.getResponse().getHeaders().get(TraceIdGlobalFilter.TRACE_ID_HEADER))
                .containsExactly("trace-002");
    }

    @Test
    @DisplayName("缺失 X-Trace-Id：不新增头")
    void noOpWhenAbsent() {
        MockServerWebExchange exchange = doFilter();
        assertThat(exchange.getResponse().getHeaders().containsKey(TraceIdGlobalFilter.TRACE_ID_HEADER))
                .isFalse();
    }

    @Test
    @DisplayName("order：紧邻 NettyWriteResponseFilter 之上（响应阶段先于写出执行）")
    void order() {
        assertThat(filter.getOrder())
                .isEqualTo(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER + 1);
    }
}
