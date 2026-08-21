package com.example.meta.web.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 创建单据定义请求（POST /api/v1/schema/doc，DC-01）。
 *
 * <p>attributes/statusDefs 松散 Map 承载（单据属性类型域宽于实体契约，
 * 发布校验在 service 层完成）；status_defs 口径以 prd/12 §四为准。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocSchemaCreateRequest(
        @NotBlank(message = "不可为空")
        @Pattern(regexp = "[a-z][a-z0-9_]{1,63}", message = "格式不正确（小写字母开头，仅限小写字母/数字/下划线）")
        String docCode,
        @NotBlank(message = "不可为空")
        String docName,
        String docType,
        String description,
        List<Map<String, Object>> attributes,
        List<Map<String, Object>> statusDefs) {
}
