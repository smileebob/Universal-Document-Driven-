package com.example.common.infrastructure.tablestore.model;

import java.util.Collections;
import java.util.List;

/**
 * 批量写结果（BatchWriteRow 为非原子操作，逐行检查行级失败，TS-02）。
 */
public final class BatchWriteResult {

    /** 单行失败明细 */
    public static final class FailedRow {

        /** 原始请求中的行序号（0 基） */
        private final int index;
        private final String table;
        private final TsPrimaryKey primaryKey;
        private final String errorMessage;

        public FailedRow(int index, String table, TsPrimaryKey primaryKey, String errorMessage) {
            this.index = index;
            this.table = table;
            this.primaryKey = primaryKey;
            this.errorMessage = errorMessage;
        }

        public int getIndex() {
            return index;
        }

        public String getTable() {
            return table;
        }

        public TsPrimaryKey getPrimaryKey() {
            return primaryKey;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return "FailedRow{index=" + index + ", table=" + table
                    + ", pk=" + primaryKey.encode() + ", error=" + errorMessage + "}";
        }
    }

    private final int total;
    private final List<FailedRow> failedRows;

    public BatchWriteResult(int total, List<FailedRow> failedRows) {
        this.total = total;
        this.failedRows = Collections.unmodifiableList(failedRows);
    }

    public int getTotal() {
        return total;
    }

    public int getSucceeded() {
        return total - failedRows.size();
    }

    public List<FailedRow> getFailedRows() {
        return failedRows;
    }

    public boolean isAllSucceeded() {
        return failedRows.isEmpty();
    }
}
