package com.example.meta.web.dto;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 修改规则请求（PUT /api/v1/rule/{uuid}，RE-02）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleUpdateRequest(
        @NotBlank(message = "不可为空")
        String ruleName,
        @NotBlank(message = "不可为空")
        String ruleType,
        String expression,
        String interfaceCode,
        Boolean enabled,
        String description) {
}
