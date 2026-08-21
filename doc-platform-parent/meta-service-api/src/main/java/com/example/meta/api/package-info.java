/**
 * 配置域对外契约包（meta-service-api）。
 *
 * <p>职责：meta-service 的 {@code @FeignClient} 接口与 DTO，供 runtime-service 依赖（A-04）；
 * 服务端实现位于 meta-service 的 controller 层 {@code InternalSchemaController}
 * （承接 /api/v1/internal/schema/**，不暴露给网关）。</p>
 *
 * <p>约束：本包只含契约声明，无实现、无 Spring Boot 依赖（除 openfeign 注解与 validation）。</p>
 */
package com.example.meta.api;
