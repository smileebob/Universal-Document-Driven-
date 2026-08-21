/**
 * 运行域业务核心层（domain）。
 *
 * <p>职责：五类校验器、规则执行器（SpEL 沙箱）、Saga 编排器、查询 DSL 翻译模型
 * （design/08 §1.3）；本层不依赖 Spring Web 与 Feign 注解、
 * 不 import 任何 Tablestore/RocketMQ SDK 类型（F-06 防腐层）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 runtime-service-api 模块。</p>
 */
package com.example.runtime.domain;
