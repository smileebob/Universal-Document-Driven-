package com.example.common.infrastructure.tablestore.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量写单行变更（BatchWriteRow 的元素，防腐层自有类型）。
 *
 * <p>支持 PUT / UPDATE / DELETE 三类行变更；批量写为非原子操作，
 * 逐行结果经 {@link BatchWriteResult} 返回（TS-02）。</p>
 */
public final class RowChange {

    /** 变更类型 */
    public enum Op {
        /** 整行覆盖写 */
        PUT,
        /** 局部列更新 */
        UPDATE,
        /** 删除行 */
        DELETE
    }

    private final Op op;
    private final String table;
    private final TsPrimaryKey primaryKey;

    /** PUT 的全量列 / UPDATE 的列变更（DELETE 时为空） */
    private final Map<String, TsColumnValue> putColumns;
    private final List<ColumnUpdate> updates;
    private final RowCondition condition;

    private RowChange(Op op, String table, TsPrimaryKey primaryKey,
                      Map<String, TsColumnValue> putColumns, List<ColumnUpdate> updates,
                      RowCondition condition) {
        if (table == null || table.isEmpty()) {
            throw new IllegalArgumentException("表名不可为空");
        }
        this.op = op;
        this.table = table;
        this.primaryKey = primaryKey;
        this.putColumns = putColumns == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(putColumns));
        this.updates = updates == null ? Collections.emptyList() : List.copyOf(updates);
        this.condition = condition == null ? RowCondition.ignore() : condition;
    }

    public static RowChange put(String table, TsRow row) {
        return put(table, row, RowCondition.ignore());
    }

    public static RowChange put(String table, TsRow row, RowCondition condition) {
        return new RowChange(Op.PUT, table, row.getPrimaryKey(), row.getColumns(), null, condition);
    }

    public static RowChange update(String table, TsPrimaryKey primaryKey,
                                   List<ColumnUpdate> updates, RowCondition condition) {
        return new RowChange(Op.UPDATE, table, primaryKey, null, updates, condition);
    }

    public static RowChange delete(String table, TsPrimaryKey primaryKey) {
        return new RowChange(Op.DELETE, table, primaryKey, null, null, RowCondition.ignore());
    }

    public Op getOp() {
        return op;
    }

    public String getTable() {
        return table;
    }

    public TsPrimaryKey getPrimaryKey() {
        return primaryKey;
    }

    public Map<String, TsColumnValue> getPutColumns() {
        return putColumns;
    }

    public List<ColumnUpdate> getUpdates() {
        return updates;
    }

    public RowCondition getCondition() {
        return condition;
    }
}
