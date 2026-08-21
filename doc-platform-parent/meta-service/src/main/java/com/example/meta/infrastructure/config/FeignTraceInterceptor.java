package com.example.meta.infrastructure.config;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用 trace_id 透传拦截器（design/11 §4.1：服务间 Feign 自动注入头）。
 *
 * <p>将当前请求 MDC 中的 traceId 以 {@code X-Trace-Id} 头透传至下游服务，
 * 保证 meta → runtime（DataRefClient 关联反查）内部调用链路不断链；
 * 无 MDC 上下文（异步任务直发）时不注入，由下游入口过滤器兜底生成。</p>
 */
@Component
public class FeignTraceInterceptor implements RequestInterceptor {

    /** 链路追踪头（与网关 TraceIdGlobalFilter / TraceIdServletFilter 同名） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 键（logback camelCase 口径） */
    public static final String MDC_TRACE_ID = "traceId";

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            template.header(TRACE_ID_HEADER, traceId);
        }
    }
}
