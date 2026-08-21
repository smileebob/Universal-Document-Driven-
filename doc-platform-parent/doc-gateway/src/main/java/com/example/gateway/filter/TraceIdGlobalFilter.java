package com.example.gateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 链路追踪 ID 注入过滤器（J-01/J-02：全链路追踪）。
 *
 * <p>请求携带 X-Trace-Id 则沿用，否则生成（32 位无连字符 UUID）；
 * 同时写入下游请求头与响应头，供下游日志 MDC 与前端排障使用。</p>
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    /** 链路追踪头（请求与响应同名） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        boolean generated = false;
        if (traceId == null || traceId.isBlank()) {
            // 无则生成：32 位无连字符 UUID
            traceId = UUID.randomUUID().toString().replace("-", "");
            generated = true;
        }
        ServerWebExchange mutated = exchange.mutate()
                .request(generated ? request.mutate().header(TRACE_ID_HEADER, traceId).build() : request)
                // 响应头同步回写，便于前端/调用方排障关联
                .response(exchange.getResponse())
                .build();
        mutated.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        // 最高优先级：保证后续过滤器（含认证）日志均带 traceId
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
