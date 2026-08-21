package com.example.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运行域微服务启动类（runtime-service，端口 8082）。
 *
 * <p>职责：数据 CRUD、五类校验、状态流转（锁+规则+Saga）、统一查询、
 * Outbox 事件、审计、运营监控（design/08 §1.1/1.3）。</p>
 *
 * <p>Feign 扫描范围仅限对方服务契约包（meta-service-api）：
 * 避免将本服务 {@code DataRefClient} 契约误注册为自调用客户端。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.example.meta.api")
@EnableScheduling
public class RuntimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuntimeServiceApplication.class, args);
    }
}
