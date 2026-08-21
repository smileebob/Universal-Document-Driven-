package com.example.common.infrastructure.tablestore.metrics;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import com.example.common.infrastructure.tablestore.LocalTransactionOps;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.model.BatchWriteResult;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowChange;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;

/**
 * TablestoreTemplate 指标装饰器（design/11 §4.2 / J-03）：包裹真实实现或仿真实现，
 * 对数据面操作记录延迟直方图与成功/失败计数（{@link TablestoreMetrics}），
 * 不改变门面契约与异常语义（异常原样上抛）。
 *
 * <p>表管理面操作（ensureTable/ensureSearchIndex）为低频启动/流水线动作，不打点。</p>
 */
public class InstrumentedTablestoreTemplate implements TablestoreTemplate {

    private final TablestoreTemplate delegate;
    private final TablestoreMetrics metrics;

    public InstrumentedTablestoreTemplate(TablestoreTemplate delegate, TablestoreMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics == null ? TablestoreMetrics.NOOP : metrics;
    }

    /** 被装饰的原始实现（健康探针/测试用） */
    public TablestoreTemplate getDelegate() {
        return delegate;
    }

    /** 打点执行包装（异常原样上抛，result=error 计数） */
    private <T> T timed(String operation, Supplier<T> action) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            T result = action.get();
            success = true;
            return result;
        } finally {
            metrics.record(operation, start, success);
        }
    }

    private void timedVoid(String operation, Runnable action) {
        timed(operation, () -> {
            action.run();
            return null;
        });
    }

    // ==================== 点查 ====================

    @Override
    public Optional<TsRow> getRow(String table, TsPrimaryKey pk) {
        return timed("get_row", () -> delegate.getRow(table, pk));
    }

    @Override
    public List<TsRow> batchGet(String table, List<TsPrimaryKey> primaryKeys) {
        return timed("batch_get", () -> delegate.batchGet(table, primaryKeys));
    }

    // ==================== 写入 ====================

    @Override
    public void putRow(String table, TsRow row) {
        timedVoid("put_row", () -> delegate.putRow(table, row));
    }

    @Override
    public void putRow(String table, TsRow row, RowCondition condition) {
        timedVoid("put_row", () -> delegate.putRow(table, row, condition));
    }

    @Override
    public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates) {
        timedVoid("update_row", () -> delegate.updateRow(table, pk, updates));
    }

    @Override
    public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates,
                          RowCondition condition) {
        timedVoid("update_row", () -> delegate.updateRow(table, pk, updates, condition));
    }

    @Override
    public void deleteRow(String table, TsPrimaryKey pk, RowCondition condition) {
        timedVoid("delete_row", () -> delegate.deleteRow(table, pk, condition));
    }

    @Override
    public BatchWriteResult batchWrite(List<RowChange> changes) {
        return timed("batch_write", () -> delegate.batchWrite(changes));
    }

    // ==================== 局部事务 ====================

    @Override
    public <T> T executeInLocalTransaction(String anchorTable, String partitionKey,
                                           Function<LocalTransactionOps, T> action) {
        return timed("local_transaction",
                () -> delegate.executeInLocalTransaction(anchorTable, partitionKey, action));
    }

    // ==================== 范围扫描 ====================

    @Override
    public List<TsRow> getRange(String table) {
        return timed("get_range", () -> delegate.getRange(table));
    }

    @Override
    public List<TsRow> getPartitionRange(String table, String partitionKey) {
        return timed("get_partition_range", () -> delegate.getPartitionRange(table, partitionKey));
    }

    // ==================== 检索 ====================

    @Override
    public SearchResult search(String table, String indexName, QuerySpec spec) {
        return timed("search", () -> delegate.search(table, indexName, spec));
    }

    // ==================== 表管理（低频，不打点） ====================

    @Override
    public void ensureTable(TableDefinition definition) {
        delegate.ensureTable(definition);
    }

    @Override
    public void ensureSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        delegate.ensureSearchIndex(tableName, indexDefinition);
    }

    @Override
    public void reconcileSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        delegate.reconcileSearchIndex(tableName, indexDefinition);
    }
}
