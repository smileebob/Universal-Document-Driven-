package com.example.common.web;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TsCrossPartitionException;
import com.example.common.infrastructure.tablestore.exception.TsOperationConflictException;
import com.example.common.model.ApiResponse;

import jakarta.validation.ConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 映射表验收（design/08 §五）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BizException 按错误码映射：40901→409、42201→422、42901→429")
    void bizException_mapping() {
        ResponseEntity<ApiResponse<Object>> conflict = handler.handleBizException(
                new BizException(ErrorCode.STATUS_CONFLICT));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().getCode()).isEqualTo(40901);

        ResponseEntity<ApiResponse<Object>> ruleFailed = handler.handleBizException(
                new BizException(ErrorCode.RULE_FAILED, "规则校验未通过",
                        java.util.Map.of("failed_rules", java.util.List.of("R1"))));
        assertThat(ruleFailed.getStatusCode().value()).isEqualTo(422);
        assertThat(ruleFailed.getBody().getData()).isNotNull();

        ResponseEntity<ApiResponse<Object>> lockBusy = handler.handleBizException(
                new BizException(ErrorCode.LOCK_BUSY));
        assertThat(lockBusy.getStatusCode().value()).isEqualTo(429);
        // design/09 §响应头约定：429 响应必须携带 Retry-After（秒）
        assertThat(lockBusy.getHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER))
                .isEqualTo("2");
        // 非 429 响应不得携带 Retry-After
        assertThat(conflict.getHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    @DisplayName("防腐层异常映射：条件失败/冲突耗尽→409、跨分区→400")
    void tablestoreExceptions_mapping() {
        ResponseEntity<ApiResponse<Object>> conditionFail = handler.handleConditionCheck(
                new TsConditionCheckException("条件更新失败"));
        assertThat(conditionFail.getStatusCode().value()).isEqualTo(409);
        assertThat(conditionFail.getBody().getCode()).isEqualTo(40901);

        ResponseEntity<ApiResponse<Object>> conflict = handler.handleOperationConflict(
                new TsOperationConflictException("冲突重试耗尽"));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<ApiResponse<Object>> crossPartition = handler.handleCrossPartition(
                new TsCrossPartitionException("跨分区"));
        assertThat(crossPartition.getStatusCode().value()).isEqualTo(400);
        assertThat(crossPartition.getBody().getCode()).isEqualTo(40002);
    }

    @Test
    @DisplayName("参数类异常：IllegalArgumentException→40002、ConstraintViolation→40002、未知异常→50000")
    void paramAndFallback_mapping() {
        ResponseEntity<ApiResponse<Object>> illegalArgument = handler.handleIllegalArgument(
                new IllegalArgumentException("分区键格式非法"));
        assertThat(illegalArgument.getStatusCode().value()).isEqualTo(400);
        assertThat(illegalArgument.getBody().getCode()).isEqualTo(40002);

        ResponseEntity<ApiResponse<Object>> constraint = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of()));
        assertThat(constraint.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<ApiResponse<Object>> fallback = handler.handleUnknown(
                new RuntimeException("意外"));
        assertThat(fallback.getStatusCode().value()).isEqualTo(500);
        assertThat(fallback.getBody().getCode()).isEqualTo(50000);
    }

    @Test
    @DisplayName("请求体 JSON 解析失败：40002/400，不得落入兜底 50000")
    void messageNotReadable_maps40002() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null);
        ResponseEntity<ApiResponse<Object>> resp = handler.handleMessageNotReadable(ex);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(40002);
    }
}
