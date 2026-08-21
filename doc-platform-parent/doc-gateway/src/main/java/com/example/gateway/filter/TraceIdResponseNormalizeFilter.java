package com.example.gateway.filter;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 响应侧 X-Trace-Id 归一化过滤器（J-01/J-02：全链路追踪，任务 #20 断点修复）。
 *
 * <p>下游 Servlet 服务（TraceIdServletFilter）与网关 {@link TraceIdGlobalFilter}
 * 都会写 X-Trace-Id 响应头，经 {@link NettyWriteResponseFilter} 拷贝下游响应头后
 * 可能出现重复值（{@code value, value}）。本过滤器在响应阶段（order 大于
 * NettyWriteResponseFilter，故其 {@code then()} 先于响应写出执行）将同名头
 * 归一化为单值，保证调用方拿到唯一 traceId。</p>
 */
@Component
public class TraceIdResponseNormalizeFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            List<String> values = headers.get(TraceIdGlobalFilter.TRACE_ID_HEADER);
            if (values != null && values.size() > 1) {
                // 重复值内容一致（同源写入），归一化为第一个
                headers.set(TraceIdGlobalFilter.TRACE_ID_HEADER, values.get(0));
            }
        }));
    }

    @Override
    public int getOrder() {
        // 大于 NettyWriteResponseFilter(-1)：响应阶段其 then() 先于响应写出执行
        return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER + 1;
    }
}
