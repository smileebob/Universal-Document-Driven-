package com.example.meta.web.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 修改单据定义请求（PUT /api/v1/schema/doc/{code}，DC-02，仅草稿态可改）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocSchemaUpdateRequest(
        @NotBlank(message = "不可为空")
        String docName,
        String docType,
        String description,
        List<Map<String, Object>> attributes,
        List<Map<String, Object>> statusDefs) {
}
