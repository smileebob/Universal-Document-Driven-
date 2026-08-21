package com.example.common.infrastructure.tablestore.exception;

/**
 * 条件更新失败（SDK {@code OTSConditionCheckFail} 的防腐层语义转换）。
 *
 * <p>语义即「他人已改 / 行已被占用」：<b>不重试</b>，上层映射 409 STATUS_CONFLICT
 * （40901）或 unique 冲突（40005），见 design/05 §3.1 F5 与 design/03 §五。</p>
 */
public class TsConditionCheckException extends TablestoreAccessException {

    private static final long serialVersionUID = 1L;

    public TsConditionCheckException(String message) {
        super(message);
    }

    public TsConditionCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
