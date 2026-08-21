package com.example.common.infrastructure.tablestore;

import java.util.ArrayList;
import java.util.List;

import com.example.common.infrastructure.tablestore.guard.WriteGuard;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;
import com.example.common.infrastructure.tablestore.model.BatchWriteResult;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowChange;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;

/**
 * TablestoreTemplate 骨架实现：批量分段（TS-02/TS-03）与写入侧红线拦截的公共逻辑，
 * 真实 SDK 适配（OtsTablestoreTemplate）与内存仿真（InMemoryTablestore）共用。
 */
public abstract class AbstractTablestoreTemplate implements TablestoreTemplate {

    /** BatchGetRow 单批上限（TS-03） */
    public static final int BATCH_GET_MAX_ROWS = 100;

    /** BatchWriteRow 单批上限（TS-02） */
    public static final int BATCH_WRITE_MAX_ROWS = 200;

    private final TableMetadataRegistry metadataRegistry;
    private final WriteGuard writeGuard;

    protected AbstractTablestoreTemplate(TableMetadataRegistry metadataRegistry) {
        this.metadataRegistry = metadataRegistry;
        this.writeGuard = new WriteGuard(metadataRegistry);
    }

    public TableMetadataRegistry getMetadataRegistry() {
        return metadataRegistry;
    }

    protected WriteGuard writeGuard() {
        return writeGuard;
    }

    // ==================== 门面公共逻辑（分段 + 校验） ====================

    @Override
    public List<TsRow> batchGet(String table, List<TsPrimaryKey> primaryKeys) {
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            return List.of();
        }
        List<TsRow> result = new ArrayList<>(primaryKeys.size());
        for (int from = 0; from < primaryKeys.size(); from += BATCH_GET_MAX_ROWS) {
            int to = Math.min(from + BATCH_GET_MAX_ROWS, primaryKeys.size());
            result.addAll(doBatchGetChunk(table, primaryKeys.subList(from, to)));
        }
        return result;
    }

    @Override
    public void putRow(String table, TsRow row) {
        putRow(table, row, RowCondition.ignore());
    }

    @Override
    public void putRow(String table, TsRow row, RowCondition condition) {
        writeGuard.validateWrite(table, row.getPrimaryKey(), row.getColumns());
        doPutRow(table, row, condition);
    }

    @Override
    public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates) {
        updateRow(table, pk, updates, RowCondition.ignore());
    }

    @Override
    public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates, RowCondition condition) {
        writeGuard.validatePartitionKey(table, pk);
        doUpdateRow(table, pk, updates, condition);
    }

    @Override
    public BatchWriteResult batchWrite(List<RowChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return new BatchWriteResult(0, List.of());
        }
        // 写入侧红线拦截（逐行）
        for (RowChange change : changes) {
            writeGuard.validatePartitionKey(change.getTable(), change.getPrimaryKey());
            if (change.getOp() == RowChange.Op.PUT) {
                writeGuard.validateRowSize(change.getTable(), change.getPutColumns());
            }
        }
        List<BatchWriteResult.FailedRow> failed = new ArrayList<>();
        for (int from = 0; from < changes.size(); from += BATCH_WRITE_MAX_ROWS) {
            int to = Math.min(from + BATCH_WRITE_MAX_ROWS, changes.size());
            List<BatchWriteResult.FailedRow> chunkFailed =
                    doBatchWriteChunk(changes.subList(from, to));
            // 行序号回填为原始请求序号
            for (BatchWriteResult.FailedRow row : chunkFailed) {
                failed.add(new BatchWriteResult.FailedRow(
                        from + row.getIndex(), row.getTable(), row.getPrimaryKey(), row.getErrorMessage()));
            }
        }
        return new BatchWriteResult(changes.size(), failed);
    }

    // ==================== 子类原语 ====================

    /** 单批点查（≤ {@link #BATCH_GET_MAX_ROWS} 行） */
    protected abstract List<TsRow> doBatchGetChunk(String table, List<TsPrimaryKey> chunk);

    /** 单行写入 */
    protected abstract void doPutRow(String table, TsRow row, RowCondition condition);

    /** 单行列更新 */
    protected abstract void doUpdateRow(String table, TsPrimaryKey pk,
                                        List<ColumnUpdate> updates, RowCondition condition);

    /** 单批写入（≤ {@link #BATCH_WRITE_MAX_ROWS} 行），返回行级失败明细（index 为本批内序号） */
    protected abstract List<BatchWriteResult.FailedRow> doBatchWriteChunk(List<RowChange> chunk);
}
