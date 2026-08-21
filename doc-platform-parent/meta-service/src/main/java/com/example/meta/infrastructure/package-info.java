/**
 * 配置域基础设施层（infrastructure）。
 *
 * <p>职责：各表 Repository（基于 doc-common TablestoreTemplate）、多元索引注册器、
 * MQ 生产者装配、Feign 降级 Bean 注册；实现 domain 层定义的接口。
 * Tablestore SDK 仅允许经 doc-common 防腐层访问（F-06）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 meta-service-api 模块。</p>
 */
package com.example.meta.infrastructure;
