package com.example.common.infrastructure.tablestore.exception;

/**
 * Tablestore 访问异常基类（防腐层语义，隔离 SDK OTSException）。
 *
 * <p>子类区分两类可裁决异常：</p>
 * <ul>
 *   <li>{@link TsConditionCheckException}：条件更新失败（409，不重试）；</li>
 *   <li>{@link TsOperationConflictException}：分区写锁竞争（409，退避重试后仍失败）。</li>
 * </ul>
 */
public class TablestoreAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TablestoreAccessException(String message) {
        super(message);
    }

    public TablestoreAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
