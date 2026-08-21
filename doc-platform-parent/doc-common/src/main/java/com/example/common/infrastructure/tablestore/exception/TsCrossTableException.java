package com.example.common.infrastructure.tablestore.exception;

/**
 * 局部事务跨表操作拒绝（OTS 硬约束：局部事务为表级作用域）。
 *
 * <p>StartLocalTransaction(anchorTable) 返回的事务 ID 仅可用于 anchorTable
 * 自身的读写；事务内访问其它表（即使同分区键）一律被真实 OTS 拒绝
 * （OTSSessionNotExist）。仿真层提前以本异常拦截，防止仿真掩盖问题。
 * 跨表写改走「单表事务 + 提交后幂等补写」编排。</p>
 */
public class TsCrossTableException extends TablestoreAccessException {

    private static final long serialVersionUID = 1L;

    public TsCrossTableException(String message) {
        super(message);
    }
}
