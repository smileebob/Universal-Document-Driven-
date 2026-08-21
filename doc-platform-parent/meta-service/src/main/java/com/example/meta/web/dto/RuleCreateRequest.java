package com.example.meta.web.dto;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 创建规则请求（POST /api/v1/rule，RE-01，uuid 服务端生成）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleCreateRequest(
        @NotBlank(message = "不可为空")
        String ruleName,
        @NotBlank(message = "不可为空")
        String ruleType,
        String expression,
        String interfaceCode,
        Boolean enabled,
        String description) {
}
