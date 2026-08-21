/**
 * 运行域基础设施层（infrastructure）。
 *
 * <p>职责：各表 Repository（基于 doc-common TablestoreTemplate）、Redis 锁/缓存实现、
 * RocketMQ 生产/消费装配、Feign 客户端与降级 Bean 装配；实现 domain 层定义的接口。
 * Tablestore SDK 仅允许经 doc-common 防腐层访问（F-06）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 runtime-service-api 模块。</p>
 */
package com.example.runtime.infrastructure;
