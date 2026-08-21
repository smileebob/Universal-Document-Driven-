package com.example.common.model;

import java.io.Serializable;

import org.slf4j.MDC;

/**
 * 统一响应包装（C 系列规范：所有对外/内部接口统一响应结构）。
 *
 * <p>结构：{@code {code, message, data, traceId}}，code=0 表示成功，
 * 业务错误码全集以 PRD 12 第三节为唯一定义处（阶段一补充 ErrorCode 枚举收口）。</p>
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功码 */
    public static final int CODE_SUCCESS = 0;

    /** 业务码：0=成功，其余为 PRD 12 第三节定义的错误码 */
    private int code;

    /** 可读文案（成功时为 "success"，失败时为错误描述） */
    private String message;

    /** 业务数据 */
    private T data;

    /** 链路追踪 ID（构造时从 MDC 回填，与日志 %X{traceId} 对齐；异常处理器显式回填可覆盖） */
    private String traceId;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        // 成功/失败路径统一回填 traceId（MDC 为空时保持 null，非 web 场景安全）
        this.traceId = MDC.get("traceId");
    }

    /**
     * 成功响应。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return code=0 的响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_SUCCESS, "success", data);
    }

    /**
     * 失败响应。
     *
     * @param code    业务错误码（PRD 12 第三节）
     * @param message 可读文案
     * @param <T>     业务数据类型
     * @return 失败响应（data 为 null）
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public boolean isSuccess() {
        return code == CODE_SUCCESS;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
