package com.example.meta.web.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 单据规则绑定保存请求（PUT /api/v1/schema/doc/{code}/rules，RE-05）。
 *
 * <p>create_rules=[rule_uuid...]（创建时机）；status_rules=[{status,rules:[uuid...]}]
 * （流转时机，status 为目标状态）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocRuleConfigRequest(
        List<String> createRules,
        List<Map<String, Object>> statusRules) {
}
