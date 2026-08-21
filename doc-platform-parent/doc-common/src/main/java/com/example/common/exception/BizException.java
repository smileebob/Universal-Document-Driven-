package com.example.common.exception;

/**
 * 统一业务异常基类（design/08 §5.1）。
 *
 * <p>业务层只抛本异常，禁止直接操作 HTTP 响应；由 GlobalExceptionHandler
 * 按 {@link ErrorCode#getHttpStatus()} 映射 HTTP 状态并回填统一响应结构。</p>
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务码（PRD 12 §三） */
    private final int code;

    /** 可选明细：failed_rules / failed_step 等（随响应 data 返回） */
    private final transient Object data;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.code = errorCode.getCode();
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.code = errorCode.getCode();
        this.data = data;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.data = null;
    }

    public int getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }

    /**
     * 对应的 HTTP 状态（按业务码反查枚举，未知码兜底 500）。
     *
     * @return HTTP 状态码
     */
    public int getHttpStatus() {
        try {
            return ErrorCode.fromCode(code).getHttpStatus();
        } catch (IllegalArgumentException e) {
            return 500;
        }
    }
}
