package com.example.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * trace_id 请求入口过滤器（J-01/J-02 全链路追踪，design/11 §4.1）。
 *
 * <p>网关 TraceIdGlobalFilter 生成/透传 {@code X-Trace-Id} 后，本过滤器在
 * Servlet 服务（meta/runtime）请求入口将其写入 MDC（键 {@code traceId}，
 * 与 logback pattern {@code %X{traceId:-}} 对齐），缺失时兜底生成；
 * 响应头同步回写便于调用方排障；请求结束清理 MDC 防线程复用串链。</p>
 */
public class TraceIdServletFilter extends OncePerRequestFilter {

    /** 链路追踪头（与网关 TraceIdGlobalFilter 同名） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 键（logback camelCase 口径，design/11 §4.3） */
    public static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            // 直连服务（未经网关）兜底生成，保证日志恒有 traceId
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
