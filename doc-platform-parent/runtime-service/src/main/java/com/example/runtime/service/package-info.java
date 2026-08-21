/**
 * 运行域应用服务层（service）。
 *
 * <p>职责：校验链编排、流转编排、Outbox 中继调度（design/08 §1.3）；
 * 本层不使用 HttpServletRequest（解耦 Web 层）。</p>
 *
 * <p>依赖方向：controller → service → domain → infrastructure，契约在独立 runtime-service-api 模块。</p>
 */
package com.example.runtime.service;
