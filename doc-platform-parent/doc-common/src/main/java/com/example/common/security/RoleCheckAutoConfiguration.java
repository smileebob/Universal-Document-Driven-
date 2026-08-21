package com.example.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 服务端角色校验装配（Boot 3 AutoConfiguration.imports 注册，仅 SERVLET 应用生效；
 * WebFlux 网关不装配——网关鉴权由 AuthGlobalFilter 承担功能入口级，design/11 §六-3）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.HandlerInterceptor")
@EnableConfigurationProperties(RoleCheckProperties.class)
public class RoleCheckAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RoleCheckInterceptor roleCheckInterceptor(RoleCheckProperties properties) {
        return new RoleCheckInterceptor(properties);
    }

    /** 拦截器注册（全部路径；未标注 {@link RequireRoles} 的接口拦截器内直接放行） */
    @Bean
    public WebMvcConfigurer roleCheckWebMvcConfigurer(RoleCheckInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**");
            }
        };
    }
}
