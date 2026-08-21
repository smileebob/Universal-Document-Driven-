/**
 * 无状态公共模块（doc-common）。
 *
 * <p>本模块承载以下公共组件：</p>
 * <ul>
 *   <li>统一响应包装 {@code ApiResponse}（见 {@link com.example.common.model.ApiResponse}）</li>
 *   <li>业务异常基类 BizException 与错误码枚举 ErrorCode（design/08 §五，阶段一充实）</li>
 *   <li>TablestoreTemplate 防腐层（design/08 §2.7，infrastructure.tablestore 包，阶段一落地）</li>
 *   <li>常量与通用工具类</li>
 * </ul>
 *
 * <p>约束：公共模块必须无状态，禁止包含任何业务逻辑；
 * Tablestore SDK 仅允许经本模块防腐层访问，业务代码禁止直接 import SDK 类型（F-06）。</p>
 */
package com.example.common;
