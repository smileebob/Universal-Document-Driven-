package com.example.common.infrastructure.tablestore;

import java.util.List;
import java.util.Optional;

import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;

/**
 * 局部事务内操作对象（TS-04：仅限同分区键读写）。
 *
 * <p>所有操作的主键分区键值必须与事务分区键一致，违反即抛
 * {@code TsCrossPartitionException}；事务内禁止远程调用、
 * Redis 操作与多元索引查询（design/03 §6.2 红线 4）。</p>
 */
public interface LocalTransactionOps {

    /**
     * 事务内点查（同分区读，可读到本事务未提交的写）。
     *
     * @param table 表名
     * @param pk    主键
     * @return 命中行
     */
    Optional<TsRow> getRow(String table, TsPrimaryKey pk);

    /**
     * 事务内整行写入（IGNORE 条件）。
     *
     * @param table 表名
     * @param row   数据行
     */
    void putRow(String table, TsRow row);

    /**
     * 事务内条件整行写入。
     *
     * @param table     表名
     * @param row       数据行
     * @param condition 行条件
     */
    void putRow(String table, TsRow row, RowCondition condition);

    /**
     * 事务内条件局部列更新（version/current_status 双条件 CAS，design/05 §2.2 T3）。
     *
     * @param table     表名
     * @param pk        主键
     * @param updates   列变更
     * @param condition 行条件
     */
    void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates, RowCondition condition);

    /**
     * 事务内删除行。
     *
     * @param table 表名
     * @param pk    主键
     */
    void deleteRow(String table, TsPrimaryKey pk);

    /**
     * 事务内同分区范围读（design/05 §2.2 T2：doc_data_history 历史行扫描，
     * 应用层据此分配递增 seq，禁用存储自增列）。
     *
     * <p>返回该表内分区键等于本事务分区键的全部行（含本事务未提交写，
     * overlay 语义与 {@link #getRow} 一致）。</p>
     *
     * @param table 表名
     * @return 同分区行集合（无命中返回空集）
     */
    List<TsRow> getPartitionRows(String table);
}
