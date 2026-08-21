package com.example.meta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 配置域微服务启动类（meta-service，端口 8081）。
 *
 * <p>职责：实体/单据定义、状态机、规则库、规则绑定、事务步骤、菜单挂载的管理与发布
 * （design/08 §1.1/1.3）。</p>
 *
 * <p>Feign 扫描范围仅限对方服务契约包（runtime-service-api）：
 * 避免将本服务 {@code MetaSchemaClient} 契约误注册为自调用客户端。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.example.runtime.api")
public class MetaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetaServiceApplication.class, args);
    }
}
