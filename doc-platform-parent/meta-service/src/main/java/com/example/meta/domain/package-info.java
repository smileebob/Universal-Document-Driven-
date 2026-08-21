/**
 * 配置域业务核心层（domain）。
 *
 * <p>职责：定义聚合、发布校验器、状态机配置校验、规则引用保护（design/08 §1.3）；
 * 本层不依赖 Spring Web 与 Feign 注解、不 import 任何 Tablestore/RocketMQ SDK 类型（F-06 防腐层）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 meta-service-api 模块。</p>
 */
package com.example.meta.domain;
