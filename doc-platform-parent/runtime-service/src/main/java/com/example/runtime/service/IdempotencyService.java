package com.example.runtime.service;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.util.IdempotencyKey;
import com.example.runtime.domain.port.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接口幂等服务（X-Idempotency-Key，design/05 §5.2 / design/09 §3.2）。
 *
 * <p>语义：无键请求直接执行（退回 CAS 兜底）；首次请求经
 * idempotent_record EXPECT_NOT_EXIST 占位（PROCESSING）→ 执行业务 →
 * 回填 DONE + 快照；重放命中 DONE 直接回放首次结果（成功回放数据、
 * 失败回放原错误码），命中 PROCESSING 返回 42901「请求处理中」；
 * 同键不同接口（api_path 不一致）拒绝互相回放。</p>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore idempotencyStore, ObjectMapper objectMapper) {
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 以幂等语义执行业务动作。
     *
     * @param idempotencyKeyHeader X-Idempotency-Key 头原值（null/空白=不启用幂等）
     * @param apiPath              接口路径（跨接口防撞）
     * @param action               业务动作（返回可 JSON 序列化结果）
     * @return 业务结果或首次成功结果回放
     * @throws BizException 40002 键非法/跨接口撞键；42901 处理中；业务异常原样穿透
     */
    public Object execute(String idempotencyKeyHeader, String apiPath, Supplier<Object> action) {
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            return action.get();
        }
        String key = IdempotencyKey.requireValid(idempotencyKeyHeader);
        Optional<IdempotencyStore.Record> existing = idempotencyStore.tryBegin(key, apiPath);
        if (existing.isPresent()) {
            return replay(existing.get(), apiPath);
        }
        return firstExecution(key, apiPath, action);
    }

    /** 重放：按既有记录状态回放首次结果 */
    private Object replay(IdempotencyStore.Record record, String apiPath) {
        if (!apiPath.equals(record.apiPath())) {
            throw new BizException(ErrorCode.TYPE_INVALID, "幂等键已经被其他接口使用，请更换 X-Idempotency-Key");
        }
        if (IdempotencyStore.STATUS_PROCESSING.equals(record.status())) {
            throw new BizException(ErrorCode.LOCK_BUSY, "请求处理中，请勿重复提交");
        }
        // DONE：回放首次结果（成功回放数据；失败回放原错误）
        if (record.errorSnapshot() != null && !record.errorSnapshot().isBlank()) {
            throw deserializeError(record.errorSnapshot());
        }
        return deserializeData(record.responseSnapshot());
    }

    /** 首次执行：成功后回填 DONE + response_snapshot，业务失败回填 error_snapshot */
    private Object firstExecution(String key, String apiPath, Supplier<Object> action) {
        try {
            Object result = action.get();
            completeQuietly(key, apiPath, serialize(result), null);
            return result;
        } catch (BizException e) {
            completeQuietly(key, apiPath, null, serializeError(e.getCode(), e.getMessage()));
            throw e;
        } catch (RuntimeException e) {
            // 未知异常同样固化结果，避免 PROCESSING 毒记录阻断后续重试
            completeQuietly(key, apiPath, null, serializeError(
                    ErrorCode.SYSTEM_ERROR.getCode(), "系统繁忙，请稍后重试"));
            throw e;
        }
    }

    /**
     * complete() 失败兼容兑底（评审修复 Minor-11）：快照回填失败仅 WARN，
     * 业务结果照常向上返回；幂等正确性由底层 EXPECT_NOT_EXIST/CAS 占位保障，
     * 最坏情形退化为重复执行（与原无幂等价），不因快照失败误报业务失败。
     */
    private void completeQuietly(String key, String apiPath,
                                 String responseSnapshot, String errorSnapshot) {
        try {
            idempotencyStore.complete(key, apiPath, responseSnapshot, errorSnapshot);
        } catch (RuntimeException e) {
            log.warn("[idempotency] 幂等记录回填失败（依赖底层 CAS 兑底）: key={}, cause={}",
                    key, e.getMessage());
        }
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[idempotency] 响应快照序列化失败，降级为不快照", e);
            return "{}";
        }
    }

    /** 错误快照序列化（评审修复 Minor-11：ObjectMapper 序列化替代手工 quote 拼接） */
    private String serializeError(int code, String message) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("code", code, "message", message == null ? "" : message));
        } catch (Exception e) {
            log.warn("[idempotency] 错误快照序列化失败，降级为不快照", e);
            return "{\"code\":" + code + "}";
        }
    }

    /** 错误快照回放（解析失败降级为 50000，避免毒快照阻塞业务） */
    private BizException deserializeError(String errorSnapshot) {
        try {
            Map<?, ?> error = objectMapper.readValue(errorSnapshot, Map.class);
            Object codeValue = error.get("code");
            int code = codeValue instanceof Number number
                    ? number.intValue() : ErrorCode.SYSTEM_ERROR.getCode();
            Object messageValue = error.get("message");
            String message = messageValue == null ? "" : String.valueOf(messageValue);
            return new BizException(ErrorCode.fromCode(code), message);
        } catch (Exception e) {
            log.warn("[idempotency] 错误快照回放失败: {}", errorSnapshot, e);
            return new BizException(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getDefaultMessage());
        }
    }

    /** 响应快照回放（解析失败降级为 50000） */
    private Object deserializeData(String responseSnapshot) {
        try {
            return objectMapper.readValue(responseSnapshot, Object.class);
        } catch (Exception e) {
            log.warn("[idempotency] 响应快照回放失败: {}", responseSnapshot, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getDefaultMessage());
        }
    }
}
