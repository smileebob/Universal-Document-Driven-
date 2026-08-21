/**
 * 配置域接入层（controller）。
 *
 * <p>职责：REST 控制器（SchemaController / RuleController / MenuController /
 * InternalSchemaController），负责参数转换与校验，并调用 service 层；
 * 本层不写业务逻辑。{@code InternalSchemaController} 承接 /api/v1/internal/schema/**
 * 内部契约（不暴露给网关，A-07）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 meta-service-api 模块。</p>
 */
package com.example.meta.controller;
