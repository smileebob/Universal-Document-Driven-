package com.example.meta.web.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 规则在线测试请求（POST /api/v1/rule/{uuid}/test，RE-02）。
 *
 * <p>context 与运行时变量同构：attributes/relations/current_status/
 * target_status/creator/sys_time（design/08 §四）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleTestRequest(
        Map<String, Object> context) {
}
