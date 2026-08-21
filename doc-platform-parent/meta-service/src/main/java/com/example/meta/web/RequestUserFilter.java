package com.example.meta.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 操作人上下文过滤器：请求入口将网关注入的 {@code X-User-Id} 写入
 * {@link RequestUserContext}，出口清理（阶段四审计操作人链路，RD-12）。
 */
@Component
@Order(1)
public class RequestUserFilter extends OncePerRequestFilter {

    /** 网关注入的操作人头（与 AuthGlobalFilter 一致） */
    private static final String USER_ID_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            RequestUserContext.set(request.getHeader(USER_ID_HEADER));
            filterChain.doFilter(request, response);
        } finally {
            RequestUserContext.clear();
        }
    }
}
