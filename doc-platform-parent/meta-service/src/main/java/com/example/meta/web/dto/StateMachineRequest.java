package com.example.meta.web.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 状态机保存请求（PUT /api/v1/schema/doc/{code}/state-machine，SM-01）。
 *
 * <p>states 元素：{status,name,editable,terminal}；transitions 元素：
 * {start,end:[...]}（end 为目标状态数组，design/02 §四）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateMachineRequest(
        @NotEmpty(message = "不可为空")
        List<Map<String, Object>> states,
        List<Map<String, Object>> transitions) {
}
