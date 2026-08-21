package com.example.common.web;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TsCrossPartitionException;
import com.example.common.infrastructure.tablestore.exception.TsOperationConflictException;
import com.example.common.model.ApiResponse;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器（design/08 §五：17 项错误码 → HTTP 映射收口）。
 *
 * <p>映射表：</p>
 * <ul>
 *   <li>{@link BizException}：按 {@code ErrorCode#getHttpStatus()} 直接映射
 *       （40001~40011→400 / 40401→404 / 40901→409 / 42201,42202→422 / 42901→429 / 50000→500）；</li>
 *   <li>请求体 JSON 解析失败（{@link HttpMessageNotReadableException}）→ 40002（格式非法）→ HTTP 400；</li>
 *   <li>参数绑定/校验异常 → 40001（必填缺失）或 40002（格式）→ HTTP 400；</li>
 *   <li>防腐层条件更新/冲突/跨分区异常 → 40901 → HTTP 409（调用方不重试或退避）；</li>
 *   <li>其余未知异常 → 50000 → HTTP 500（日志 ERROR 保留完整堆栈）。</li>
 * </ul>
 *
 * <p>经 doc-common CommonWebAutoConfiguration 自动注册（SERVLET 应用），
 * 两服务无需重复声明；如需定制可自行定义同类型 Bean 覆盖。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 429 Retry-After 建议秒数（design/09 §响应头约定：锁冲突 1~3s，实现补充 B4） */
    private static final String RETRY_AFTER_SECONDS = "2";

    /** 业务异常：按错误码映射 HTTP 状态 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBizException(BizException e) {
        ApiResponse<Object> body = ApiResponse.fail(e.getCode(), e.getMessage());
        if (e.getData() != null) {
            body.setData(e.getData());
        }
        log.warn("[biz-exception] code={}, message={}", e.getCode(), e.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getHttpStatus());
        if (e.getHttpStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            // design/09 §响应头约定：全部 429 响应携带 Retry-After（秒），前端不自动重试仅提示
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(body);
    }

    /**
     * 请求体 JSON 解析失败（body 含非法转义/截断/非 JSON）→ 40002/400。

     * <p>客户端错误不得落入兜底 50000：前端可据此提示「请求格式非法」而非误判服务故障。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[request-body] JSON 解析失败: {}", e.getMessage());
        return build(ErrorCode.TYPE_INVALID, "请求体格式非法，应为合法 JSON");
    }

    /** @RequestBody @Valid 校验失败 → 40001/400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    /** 表单/查询参数绑定失败 → 40001/400 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Object>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    /** @RequestParam/@PathVariable 约束校验失败 → 40002/400 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return build(ErrorCode.TYPE_INVALID, message);
    }

    /** 非法参数（防腐层分区键/查询条件校验等）→ 40002/400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException e) {
        return build(ErrorCode.TYPE_INVALID, e.getMessage());
    }

    /** 条件更新失败（version CAS / unique 冲突）→ 40901/409，调用方不重试 */
    @ExceptionHandler(TsConditionCheckException.class)
    public ResponseEntity<ApiResponse<Object>> handleConditionCheck(TsConditionCheckException e) {
        log.warn("[tablestore] 条件更新失败: {}", e.getMessage());
        return build(ErrorCode.STATUS_CONFLICT, ErrorCode.STATUS_CONFLICT.getDefaultMessage());
    }

    /** 局部事务冲突重试耗尽 → 40901/409 */
    @ExceptionHandler(TsOperationConflictException.class)
    public ResponseEntity<ApiResponse<Object>> handleOperationConflict(TsOperationConflictException e) {
        log.warn("[tablestore] 行操作冲突重试耗尽: {}", e.getMessage());
        return build(ErrorCode.STATUS_CONFLICT, ErrorCode.STATUS_CONFLICT.getDefaultMessage());
    }

    /** 局部事务跨分区编程错误 → 40002/400 */
    @ExceptionHandler(TsCrossPartitionException.class)
    public ResponseEntity<ApiResponse<Object>> handleCrossPartition(TsCrossPartitionException e) {
        log.error("[tablestore] 局部事务跨分区操作: {}", e.getMessage());
        return build(ErrorCode.TYPE_INVALID, e.getMessage());
    }

    /** 兜底：未知异常 → 50000/500（完整堆栈进日志，响应不暴露内部细节） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception e) {
        log.error("[system-error] 未分类异常", e);
        return build(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getDefaultMessage());
    }

    private ResponseEntity<ApiResponse<Object>> badRequest(String message) {
        return build(ErrorCode.REQUIRED_MISSING, message);
    }

    private ResponseEntity<ApiResponse<Object>> build(ErrorCode errorCode, String message) {
        ApiResponse<Object> body = ApiResponse.fail(errorCode.getCode(), message);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
