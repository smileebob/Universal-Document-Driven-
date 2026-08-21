package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API 网关启动类（doc-gateway，WebFlux 响应式，端口 8080）。
 *
 * <p>唯一外部入口：路由（meta-route / runtime-route）、JWT 认证（AuthGlobalFilter）、
 * 限流、审计埋点（A-07，GW 系列）；无持久化，禁连 Tablestore；
 * /api/v1/internal/** 不配路由，仅内网服务间 Feign 直达。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
