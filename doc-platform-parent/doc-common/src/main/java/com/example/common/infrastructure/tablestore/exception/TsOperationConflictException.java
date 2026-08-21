package com.example.common.infrastructure.tablestore.exception;

/**
 * 行操作冲突（SDK {@code OTSRowOperationConflict} 的防腐层语义转换）。
 *
 * <p>局部事务期间分区键持写锁，并发写收到本异常。模板内部已按
 * 50ms / 150ms / 450ms 指数退避重试 3 次（design/03 §6.3），抛出即重试耗尽：
 * 上层映射 409 STATUS_CONFLICT（40901）并告警核对分布式锁是否失效。</p>
 */
public class TsOperationConflictException extends TablestoreAccessException {

    private static final long serialVersionUID = 1L;

    public TsOperationConflictException(String message) {
        super(message);
    }

    public TsOperationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
