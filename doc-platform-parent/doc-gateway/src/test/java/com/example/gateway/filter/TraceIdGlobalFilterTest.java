package com.example.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * TraceIdGlobalFilter 单元测试（阶段一出口修复补齐网关测试缺口）。
 *
 * <p>覆盖矩阵：无 X-Trace-Id 生成 32 位无连字符 UUID 并同步写请求头与响应头；
 * 携带则原样透传；最高优先级排序。</p>
 */
class TraceIdGlobalFilterTest {

    private final TraceIdGlobalFilter filter = new TraceIdGlobalFilter();

    /** 记录 chain 收到的请求 */
    private ServerHttpRequest chainedRequest;

    private MockServerWebExchange doFilter(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ex -> {
            chainedRequest = ex.getRequest();
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    @DisplayName("无 X-Trace-Id：生成 32 位无连字符 UUID，请求头与响应头同步写入")
    void generateWhenAbsent() {
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x").build());
        String downstreamTraceId =
                chainedRequest.getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER);
        String responseTraceId =
                exchange.getResponse().getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER);
        assertThat(downstreamTraceId).isNotBlank().hasSize(32).doesNotContain("-");
        assertThat(responseTraceId).isEqualTo(downstreamTraceId);
    }

    @Test
    @DisplayName("携带 X-Trace-Id：原样透传，请求头不被改写")
    void passthroughWhenPresent() {
        String given = "trace-fixed-0001";
        MockServerWebExchange exchange = doFilter(
                MockServerHttpRequest.get("/api/v1/data/x")
                        .header(TraceIdGlobalFilter.TRACE_ID_HEADER, given).build());
        assertThat(chainedRequest.getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER))
                .isEqualTo(given);
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER))
                .isEqualTo(given);
    }

    @Test
    @DisplayName("空白 X-Trace-Id：视同缺失重新生成")
    void blankTreatedAsAbsent() {
        doFilter(MockServerHttpRequest.get("/api/v1/data/x")
                .header(TraceIdGlobalFilter.TRACE_ID_HEADER, "   ").build());
        assertThat(chainedRequest.getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER))
                .isNotBlank().hasSize(32);
    }

    @Test
    @DisplayName("过滤器顺序：最高优先级（保证拒绝场景日志亦带 traceId）")
    void order() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
