/**
 * 配置域应用服务层（service）。
 *
 * <p>职责：流程编排、发布事务边界、缓存失效触发；
 * 本层不使用 HttpServletRequest（解耦 Web 层）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 meta-service-api 模块。</p>
 */
package com.example.meta.service;
