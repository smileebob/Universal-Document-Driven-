package com.example.common.infrastructure.tablestore.exception;

/**
 * 局部事务跨分区操作拒绝（TS-04：局部事务仅限单一分区键）。
 *
 * <p>事务内操作对象的主键分区键值与事务分区键不一致时抛出；
 * 跨分区一致性一律改走编排式 Saga 或补偿顺序（design/03 §六红线 3）。</p>
 */
public class TsCrossPartitionException extends TablestoreAccessException {

    private static final long serialVersionUID = 1L;

    public TsCrossPartitionException(String message) {
        super(message);
    }
}
