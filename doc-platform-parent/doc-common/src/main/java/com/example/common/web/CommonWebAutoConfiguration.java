package com.example.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 公共 Web 装配（Boot 3 AutoConfiguration.imports 注册）：
 * SERVLET 应用自动获得 GlobalExceptionHandler（design/08 §五映射表）与
 * trace_id MDC 入口过滤器（design/11 §4.1），meta-service / runtime-service
 * 无需重复声明。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * trace_id MDC 入口过滤器注册（最高优先级，先于业务过滤器，
     * 保证后续过滤器/控制器日志均带 traceId）。
     */
    @Bean
    @ConditionalOnMissingBean(TraceIdServletFilter.class)
    public FilterRegistrationBean<TraceIdServletFilter> traceIdServletFilterRegistration() {
        FilterRegistrationBean<TraceIdServletFilter> registration =
                new FilterRegistrationBean<>(new TraceIdServletFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdServletFilter");
        return registration;
    }
}
