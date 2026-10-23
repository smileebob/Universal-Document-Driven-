package com.example.common.infrastructure.tablestore.ots;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.TableStoreException;
import com.alicloud.openservices.tablestore.model.BatchGetRowRequest;
import com.alicloud.openservices.tablestore.model.BatchGetRowResponse;
import com.alicloud.openservices.tablestore.model.BatchWriteRowRequest;
import com.alicloud.openservices.tablestore.model.BatchWriteRowResponse;
import com.alicloud.openservices.tablestore.model.Column;
import com.alicloud.openservices.tablestore.model.CreateTableRequest;
import com.alicloud.openservices.tablestore.model.DeleteRowRequest;
import com.alicloud.openservices.tablestore.model.GetRangeRequest;
import com.alicloud.openservices.tablestore.model.GetRangeResponse;
import com.alicloud.openservices.tablestore.model.GetRowRequest;
import com.alicloud.openservices.tablestore.model.GetRowResponse;
import com.alicloud.openservices.tablestore.model.MultiRowQueryCriteria;
import com.alicloud.openservices.tablestore.model.PrimaryKey;
import com.alicloud.openservices.tablestore.model.PrimaryKeyBuilder;
import com.alicloud.openservices.tablestore.model.PrimaryKeySchema;
import com.alicloud.openservices.tablestore.model.PrimaryKeyType;
import com.alicloud.openservices.tablestore.model.PrimaryKeyValue;
import com.alicloud.openservices.tablestore.model.PutRowRequest;
import com.alicloud.openservices.tablestore.model.RangeRowQueryCriteria;
import com.alicloud.openservices.tablestore.model.Row;
import com.alicloud.openservices.tablestore.model.RowDeleteChange;
import com.alicloud.openservices.tablestore.model.RowPutChange;
import com.alicloud.openservices.tablestore.model.RowUpdateChange;
import com.alicloud.openservices.tablestore.model.SingleRowQueryCriteria;
import com.alicloud.openservices.tablestore.model.TableMeta;
import com.alicloud.openservices.tablestore.model.TableOptions;
import com.alicloud.openservices.tablestore.model.UpdateRowRequest;
import com.alicloud.openservices.tablestore.model.Direction;
import com.alicloud.openservices.tablestore.model.search.CreateSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.DeleteSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.DescribeSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.DescribeSearchIndexResponse;
import com.alicloud.openservices.tablestore.model.search.FieldSchema;
import com.alicloud.openservices.tablestore.model.search.FieldType;
import com.alicloud.openservices.tablestore.model.search.IndexSchema;
import com.alicloud.openservices.tablestore.model.search.ListSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.search.ListSearchIndexResponse;
import com.alicloud.openservices.tablestore.model.search.SearchIndexInfo;
import com.alicloud.openservices.tablestore.model.search.SearchRequest;
import com.alicloud.openservices.tablestore.model.search.SearchResponse;
import com.alicloud.openservices.tablestore.model.search.SyncStat;
import com.alicloud.openservices.tablestore.model.search.UpdateSearchIndexRequest;
import com.alicloud.openservices.tablestore.model.AbortTransactionRequest;
import com.alicloud.openservices.tablestore.model.CommitTransactionRequest;
import com.alicloud.openservices.tablestore.model.StartLocalTransactionRequest;
import com.alicloud.openservices.tablestore.model.StartLocalTransactionResponse;
import com.example.common.infrastructure.tablestore.AbstractTablestoreTemplate;
import com.example.common.infrastructure.tablestore.LocalTransactionOps;
import com.example.common.infrastructure.tablestore.exception.TablestoreAccessException;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TsCrossPartitionException;
import com.example.common.infrastructure.tablestore.exception.TsCrossTableException;
import com.example.common.infrastructure.tablestore.exception.TsOperationConflictException;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.PkColumnDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;
import com.example.common.infrastructure.tablestore.model.BatchWriteResult;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowChange;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;

/**
 * TablestoreTemplate 真实 SDK 适配实现（唯一生产实现，F-06）。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>{@code OTSConditionCheckFail} → {@link TsConditionCheckException}（不重试，上层 409）；</li>
 *   <li>{@code OTSRowOperationConflict} → 局部事务入口按 50/150/450ms 退避重试 3 次，
 *       耗尽后抛 {@link TsOperationConflictException}（design/03 §6.3）；</li>
 *   <li>局部事务仅收口同分区键操作，跨分区抛 {@link TsCrossPartitionException}；</li>
 *   <li>其余 SDK 异常统一包装为 {@link TablestoreAccessException}，不泄漏 SDK 类型。</li>
 * </ul>
 */
public class OtsTablestoreTemplate extends AbstractTablestoreTemplate {

    private static final Logger log = LoggerFactory.getLogger(OtsTablestoreTemplate.class);

    /** 局部事务冲突重试间隔（design/03 §6.3：指数退避 3 次） */
    private static final long[] CONFLICT_RETRY_BACKOFF_MS = {50L, 150L, 450L};

    /** GetRange 单页行数上限（配置表全量遍历场景，百级行一次即回） */
    private static final int GET_RANGE_PAGE_LIMIT = 5000;

    private final SyncClient client;

    public OtsTablestoreTemplate(SyncClient client, TableMetadataRegistry metadataRegistry) {
        super(metadataRegistry);
        this.client = client;
    }

    // ==================== 点查 ====================

    @Override
    public Optional<TsRow> getRow(String table, TsPrimaryKey pk) {
        try {
            SingleRowQueryCriteria criteria = new SingleRowQueryCriteria(table, OtsTypes.toSdkPk(pk));
            criteria.setMaxVersions(1);
            GetRowResponse response = client.getRow(new GetRowRequest(criteria));
            Row row = response.getRow();
            return row == null ? Optional.empty() : Optional.of(OtsTypes.fromSdkRow(row));
        } catch (TableStoreException e) {
            throw translate(e, "GetRow: table=" + table);
        }
    }

    @Override
    protected List<TsRow> doBatchGetChunk(String table, List<TsPrimaryKey> chunk) {
        try {
            MultiRowQueryCriteria criteria = new MultiRowQueryCriteria(table);
            criteria.setMaxVersions(1);
            for (TsPrimaryKey pk : chunk) {
                criteria.addRow(OtsTypes.toSdkPk(pk));
            }
            BatchGetRowRequest request = new BatchGetRowRequest();
            request.addMultiRowQueryCriteria(criteria);
            BatchGetRowResponse response = client.batchGetRow(request);
            List<TsRow> rows = new ArrayList<>(chunk.size());
            for (BatchGetRowResponse.RowResult result : response.getBatchGetRowResult(table)) {
                if (result.isSucceed() && result.getRow() != null) {
                    rows.add(OtsTypes.fromSdkRow(result.getRow()));
                }
            }
            return rows;
        } catch (TableStoreException e) {
            throw translate(e, "BatchGetRow: table=" + table);
        }
    }

    // ==================== 写入 ====================

    @Override
    protected void doPutRow(String table, TsRow row, RowCondition condition) {
        try {
            RowPutChange change = new RowPutChange(table, OtsTypes.toSdkPk(row.getPrimaryKey()));
            for (var entry : row.getColumns().entrySet()) {
                change.addColumn(new Column(entry.getKey(), OtsTypes.toSdkColumnValue(entry.getValue())));
            }
            change.setCondition(OtsTypes.toSdkCondition(condition));
            client.putRow(new PutRowRequest(change));
        } catch (TableStoreException e) {
            throw translate(e, "PutRow: table=" + table);
        }
    }

    @Override
    protected void doUpdateRow(String table, TsPrimaryKey pk,
                               List<ColumnUpdate> updates, RowCondition condition) {
        try {
            RowUpdateChange change = new RowUpdateChange(table, OtsTypes.toSdkPk(pk));
            for (ColumnUpdate update : updates) {
                if (update.isDelete()) {
                    change.deleteColumns(update.getName());
                } else {
                    change.put(update.getName(), OtsTypes.toSdkColumnValue(update.getValue()));
                }
            }
            change.setCondition(OtsTypes.toSdkCondition(condition));
            client.updateRow(new UpdateRowRequest(change));
        } catch (TableStoreException e) {
            throw translate(e, "UpdateRow: table=" + table);
        }
    }

    @Override
    public void deleteRow(String table, TsPrimaryKey pk, RowCondition condition) {
        writeGuard().validatePartitionKey(table, pk);
        try {
            RowDeleteChange change = new RowDeleteChange(table, OtsTypes.toSdkPk(pk));
            // 条件删除（评审修复 Major-6）：OTSConditionCheckFail → TsConditionCheckException
            change.setCondition(OtsTypes.toSdkCondition(condition));
            client.deleteRow(new DeleteRowRequest(change));
        } catch (TableStoreException e) {
            throw translate(e, "DeleteRow: table=" + table);
        }
    }

    @Override
    protected List<BatchWriteResult.FailedRow> doBatchWriteChunk(List<RowChange> chunk) {
        try {
            BatchWriteRowRequest request = new BatchWriteRowRequest();
            for (RowChange change : chunk) {
                switch (change.getOp()) {
                    case PUT: {
                        RowPutChange put = new RowPutChange(change.getTable(),
                                OtsTypes.toSdkPk(change.getPrimaryKey()));
                        for (var entry : change.getPutColumns().entrySet()) {
                            put.addColumn(new Column(entry.getKey(),
                                    OtsTypes.toSdkColumnValue(entry.getValue())));
                        }
                        put.setCondition(OtsTypes.toSdkCondition(change.getCondition()));
                        request.addRowChange(put);
                        break;
                    }
                    case UPDATE: {
                        RowUpdateChange update = new RowUpdateChange(change.getTable(),
                                OtsTypes.toSdkPk(change.getPrimaryKey()));
                        for (ColumnUpdate columnUpdate : change.getUpdates()) {
                            if (columnUpdate.isDelete()) {
                                update.deleteColumns(columnUpdate.getName());
                            } else {
                                update.put(columnUpdate.getName(),
                                        OtsTypes.toSdkColumnValue(columnUpdate.getValue()));
                            }
                        }
                        update.setCondition(OtsTypes.toSdkCondition(change.getCondition()));
                        request.addRowChange(update);
                        break;
                    }
                    case DELETE: {
                        RowDeleteChange delete = new RowDeleteChange(change.getTable(),
                                OtsTypes.toSdkPk(change.getPrimaryKey()));
                        request.addRowChange(delete);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("不支持的批量写操作: " + change.getOp());
                }
            }
            BatchWriteRowResponse response = client.batchWriteRow(request);
            List<BatchWriteResult.FailedRow> failed = new ArrayList<>();
            List<BatchWriteRowResponse.RowResult> failedResults = response.getFailedRows();
            for (BatchWriteRowResponse.RowResult result : failedResults) {
                RowChange origin = chunk.get(result.getIndex());
                failed.add(new BatchWriteResult.FailedRow(result.getIndex(), origin.getTable(),
                        origin.getPrimaryKey(),
                        result.getError() == null ? "未知错误" : result.getError().getMessage()));
            }
            return failed;
        } catch (TableStoreException e) {
            throw translate(e, "BatchWriteRow: rows=" + chunk.size());
        }
    }

    // ==================== 局部事务 ====================

    @Override
    public <T> T executeInLocalTransaction(String anchorTable, String partitionKey,
                                           Function<LocalTransactionOps, T> action) {
        writeGuard().validatePartitionValue(anchorTable, partitionKey);
        RuntimeException lastConflict = null;
        for (int attempt = 0; attempt <= CONFLICT_RETRY_BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                sleepQuietly(CONFLICT_RETRY_BACKOFF_MS[attempt - 1]);
            }
            try {
                return doLocalTransaction(anchorTable, partitionKey, action);
            } catch (TsOperationConflictException e) {
                lastConflict = e;
                log.warn("[tablestore][local-tx] 分区写锁竞争，退避重试 {}/{}: partitionKey={}",
                        attempt, CONFLICT_RETRY_BACKOFF_MS.length, partitionKey);
            }
        }
        throw new TsOperationConflictException(
                "局部事务冲突重试耗尽（3 次退避）: anchorTable=" + anchorTable
                        + ", partitionKey=" + partitionKey, lastConflict);
    }

    private <T> T doLocalTransaction(String anchorTable, String partitionKey,
                                     Function<LocalTransactionOps, T> action) {
        String partitionColumnName = getMetadataRegistry().get(anchorTable)
                .map(TableDefinition::partitionKeyName)
                .orElse("partition_key");
        StartLocalTransactionResponse started;
        try {
            StartLocalTransactionRequest startRequest = new StartLocalTransactionRequest(
                    anchorTable,
                    com.alicloud.openservices.tablestore.model.PrimaryKeyBuilder.createPrimaryKeyBuilder()
                            .addPrimaryKeyColumn(partitionColumnName,
                                    com.alicloud.openservices.tablestore.model.PrimaryKeyValue
                                            .fromString(partitionKey))
                            .build());
            started = client.startLocalTransaction(startRequest);
        } catch (TableStoreException e) {
            throw translate(e, "StartLocalTransaction: table=" + anchorTable);
        }
        String transactionId = started.getTransactionID();
        LocalTransactionOps ops = new OtsLocalTransactionOps(transactionId, anchorTable, partitionKey);
        try {
            T result = action.apply(ops);
            try {
                client.commitTransaction(
                        new CommitTransactionRequest(transactionId));
            } catch (TableStoreException e) {
                throw translate(e, "CommitTransaction: txId=" + transactionId);
            }
            return result;
        } catch (RuntimeException e) {
            tryAbortQuietly(transactionId);
            throw e;
        }
    }

    private void tryAbortQuietly(String transactionId) {
        try {
            client.abortTransaction(
                    new AbortTransactionRequest(transactionId));
        } catch (RuntimeException abortError) {
            log.error("[tablestore][local-tx] AbortTransaction 失败（服务端超时自动回滚兜底）: txId={}",
                    transactionId, abortError);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TablestoreAccessException("局部事务退避等待被中断", e);
        }
    }

    /** 事务内操作对象：全部读写携带 transactionId，表级作用域与分区键一致性前置校验。 */
    private final class OtsLocalTransactionOps implements LocalTransactionOps {

        private final String transactionId;
        private final String anchorTable;
        private final String partitionKey;

        private OtsLocalTransactionOps(String transactionId, String anchorTable, String partitionKey) {
            this.transactionId = transactionId;
            this.anchorTable = anchorTable;
            this.partitionKey = partitionKey;
        }

        @Override
        public Optional<TsRow> getRow(String table, TsPrimaryKey pk) {
            requireAnchorTable(table);
            requireSamePartition(table, pk);
            try {
                SingleRowQueryCriteria criteria = new SingleRowQueryCriteria(table, OtsTypes.toSdkPk(pk));
                criteria.setMaxVersions(1);
                GetRowRequest request = new GetRowRequest(criteria);
                request.setTransactionId(transactionId);
                Row row = client.getRow(request).getRow();
                return row == null ? Optional.empty() : Optional.of(OtsTypes.fromSdkRow(row));
            } catch (TableStoreException e) {
                throw translate(e, "TxGetRow: table=" + table);
            }
        }

        @Override
        public void putRow(String table, TsRow row) {
            putRow(table, row, RowCondition.ignore());
        }

        @Override
        public void putRow(String table, TsRow row, RowCondition condition) {
            requireAnchorTable(table);
            requireSamePartition(table, row.getPrimaryKey());
            writeGuard().validateWrite(table, row.getPrimaryKey(), row.getColumns());
            try {
                RowPutChange change = new RowPutChange(table, OtsTypes.toSdkPk(row.getPrimaryKey()));
                for (var entry : row.getColumns().entrySet()) {
                    change.addColumn(new Column(entry.getKey(), OtsTypes.toSdkColumnValue(entry.getValue())));
                }
                change.setCondition(OtsTypes.toSdkCondition(condition));
                PutRowRequest request = new PutRowRequest(change);
                request.setTransactionId(transactionId);
                client.putRow(request);
            } catch (TableStoreException e) {
                throw translate(e, "TxPutRow: table=" + table);
            }
        }

        @Override
        public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates,
                              RowCondition condition) {
            requireAnchorTable(table);
            requireSamePartition(table, pk);
            writeGuard().validatePartitionKey(table, pk);
            try {
                RowUpdateChange change = new RowUpdateChange(table, OtsTypes.toSdkPk(pk));
                for (ColumnUpdate update : updates) {
                    if (update.isDelete()) {
                        change.deleteColumns(update.getName());
                    } else {
                        change.put(update.getName(), OtsTypes.toSdkColumnValue(update.getValue()));
                    }
                }
                change.setCondition(OtsTypes.toSdkCondition(condition));
                UpdateRowRequest request = new UpdateRowRequest(change);
                request.setTransactionId(transactionId);
                client.updateRow(request);
            } catch (TableStoreException e) {
                throw translate(e, "TxUpdateRow: table=" + table);
            }
        }

        @Override
        public void deleteRow(String table, TsPrimaryKey pk) {
            requireAnchorTable(table);
            requireSamePartition(table, pk);
            writeGuard().validatePartitionKey(table, pk);
            try {
                RowDeleteChange change = new RowDeleteChange(table, OtsTypes.toSdkPk(pk));
                DeleteRowRequest request = new DeleteRowRequest(change);
                request.setTransactionId(transactionId);
                client.deleteRow(request);
            } catch (TableStoreException e) {
                throw translate(e, "TxDeleteRow: table=" + table);
            }
        }

        @Override
        public List<TsRow> getPartitionRows(String table) {
            requireAnchorTable(table);
            TableDefinition definition = getMetadataRegistry().get(table)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "表未注册元数据，无法构造事务内 GetRange 边界: table=" + table));
            try {
                RangeRowQueryCriteria criteria = new RangeRowQueryCriteria(table);
                criteria.setDirection(Direction.FORWARD);
                criteria.setMaxVersions(1);
                criteria.setLimit(GET_RANGE_PAGE_LIMIT);
                criteria.setInclusiveStartPrimaryKey(partitionBoundary(definition, PrimaryKeyValue.INF_MIN));
                criteria.setExclusiveEndPrimaryKey(partitionBoundary(definition, PrimaryKeyValue.INF_MAX));
                List<TsRow> result = new ArrayList<>();
                PrimaryKey nextStart;
                do {
                    GetRangeRequest request = new GetRangeRequest(criteria);
                    // 事务内读取：携带 transactionId（同分区约束由服务端保证）
                    request.setTransactionId(transactionId);
                    GetRangeResponse response = client.getRange(request);
                    for (Row row : response.getRows()) {
                        result.add(OtsTypes.fromSdkRow(row));
                    }
                    nextStart = response.getNextStartPrimaryKey();
                    if (nextStart != null) {
                        criteria.setInclusiveStartPrimaryKey(nextStart);
                    }
                } while (nextStart != null);
                return result;
            } catch (TableStoreException e) {
                throw translate(e, "TxGetRange: table=" + table);
            }
        }

        /** 同分区范围边界：第一主键列固定为事务分区键，其余列 INF_MIN/INF_MAX */
        private PrimaryKey partitionBoundary(TableDefinition definition, PrimaryKeyValue bound) {
            PrimaryKeyBuilder builder = PrimaryKeyBuilder.createPrimaryKeyBuilder();
            boolean first = true;
            for (PkColumnDef pk : definition.getPrimaryKey()) {
                if (first) {
                    builder.addPrimaryKeyColumn(pk.getName(), PrimaryKeyValue.fromString(partitionKey));
                    first = false;
                } else {
                    builder.addPrimaryKeyColumn(pk.getName(), bound);
                }
            }
            return builder.build();
        }

        /**
         * 表级作用域校验（OTS 硬约束）：StartLocalTransaction 返回的事务 ID
         * 仅可用于 anchorTable 自身读写，跨表操作服务端报 OTSSessionNotExist；
         * 此处前置拦截提供清晰错误，避免事务内半程失败后才暴露。
         */
        private void requireAnchorTable(String table) {
            if (!anchorTable.equals(table)) {
                throw new TsCrossTableException(
                        "局部事务跨表操作被拒绝（OTS 表级作用域硬约束）: anchorTable="
                                + anchorTable + ", 实际=" + table
                                + "；跨表写请移至事务提交后幂等补写");
            }
        }

        private void requireSamePartition(String table, TsPrimaryKey pk) {
            if (!partitionKey.equals(pk.partitionValueAsString())) {
                throw new TsCrossPartitionException(
                        "局部事务跨分区操作被拒绝: table=" + table + ", 期望分区键=" + partitionKey
                                + ", 实际=" + pk.partitionValueAsString());
            }
        }
    }

    // ==================== 范围扫描 ====================

    @Override
    public List<TsRow> getRange(String table) {
        TableDefinition definition = getMetadataRegistry().get(table)
                .orElseThrow(() -> new IllegalArgumentException(
                        "表未注册元数据，无法构造 GetRange 边界: table=" + table));
        try {
            RangeRowQueryCriteria criteria = new RangeRowQueryCriteria(table);
            criteria.setDirection(Direction.FORWARD);
            criteria.setMaxVersions(1);
            criteria.setLimit(GET_RANGE_PAGE_LIMIT);
            criteria.setInclusiveStartPrimaryKey(rangeBoundary(definition, PrimaryKeyValue.INF_MIN));
            criteria.setExclusiveEndPrimaryKey(rangeBoundary(definition, PrimaryKeyValue.INF_MAX));
            List<TsRow> result = new ArrayList<>();
            PrimaryKey nextStart;
            do {
                GetRangeResponse response = client.getRange(new GetRangeRequest(criteria));
                for (Row row : response.getRows()) {
                    result.add(OtsTypes.fromSdkRow(row));
                }
                nextStart = response.getNextStartPrimaryKey();
                if (nextStart != null) {
                    criteria.setInclusiveStartPrimaryKey(nextStart);
                }
            } while (nextStart != null);
            return result;
        } catch (TableStoreException e) {
            throw translate(e, "GetRange: table=" + table);
        }
    }

    @Override
    public List<TsRow> getPartitionRange(String table, String partitionKey) {
        TableDefinition definition = getMetadataRegistry().get(table)
                .orElseThrow(() -> new IllegalArgumentException(
                        "表未注册元数据，无法构造分区 GetRange 边界: table=" + table));
        try {
            RangeRowQueryCriteria criteria = new RangeRowQueryCriteria(table);
            criteria.setDirection(Direction.FORWARD);
            criteria.setMaxVersions(1);
            criteria.setLimit(GET_RANGE_PAGE_LIMIT);
            criteria.setInclusiveStartPrimaryKey(
                    partitionRangeBoundary(definition, partitionKey, PrimaryKeyValue.INF_MIN));
            criteria.setExclusiveEndPrimaryKey(
                    partitionRangeBoundary(definition, partitionKey, PrimaryKeyValue.INF_MAX));
            List<TsRow> result = new ArrayList<>();
            PrimaryKey nextStart;
            do {
                GetRangeResponse response = client.getRange(new GetRangeRequest(criteria));
                for (Row row : response.getRows()) {
                    result.add(OtsTypes.fromSdkRow(row));
                }
                nextStart = response.getNextStartPrimaryKey();
                if (nextStart != null) {
                    criteria.setInclusiveStartPrimaryKey(nextStart);
                }
            } while (nextStart != null);
            return result;
        } catch (TableStoreException e) {
            throw translate(e, "PartitionGetRange: table=" + table);
        }
    }

    /** 分区级范围边界：第一主键列固定分区键值，其余列 INF_MIN / INF_MAX */
    private static PrimaryKey partitionRangeBoundary(TableDefinition definition,
                                                     String partitionKey, PrimaryKeyValue bound) {
        PrimaryKeyBuilder builder = PrimaryKeyBuilder.createPrimaryKeyBuilder();
        boolean first = true;
        for (PkColumnDef pk : definition.getPrimaryKey()) {
            if (first) {
                builder.addPrimaryKeyColumn(pk.getName(), PrimaryKeyValue.fromString(partitionKey));
                first = false;
            } else {
                builder.addPrimaryKeyColumn(pk.getName(), bound);
            }
        }
        return builder.build();
    }

    /** 按表定义主键列构造 INF_MIN / INF_MAX 边界主键 */
    private static PrimaryKey rangeBoundary(TableDefinition definition, PrimaryKeyValue bound) {
        PrimaryKeyBuilder builder = PrimaryKeyBuilder.createPrimaryKeyBuilder();
        for (PkColumnDef pk : definition.getPrimaryKey()) {
            builder.addPrimaryKeyColumn(pk.getName(), bound);
        }
        return builder.build();
    }

    // ==================== 检索 ====================

    @Override
    public SearchResult search(String table, String indexName, QuerySpec spec) {
        // offset 模式（评审修复 Major-4）：SDK 无合法 offset 通道，
        // 以 token 循环翻页跳过偏移段（offset+limit ≤ 2000 约束下开销可控）
        if (spec.getOffset() > 0) {
            return searchWithOffset(table, indexName, spec);
        }
        try {
            SearchResponse response = client.search(buildSearchRequest(table, indexName, spec));
            List<TsRow> rows = response.getRows().stream()
                    .map(OtsTypes::fromSdkRow)
                    .collect(Collectors.toList());
            String nextToken = OtsQueryTranslator.encodeToken(response.getNextToken());
            long totalCount = spec.isGetTotalCount()
                    ? response.getTotalCount()
                    : SearchResult.TOTAL_COUNT_NOT_REQUESTED;
            return new SearchResult(rows, nextToken, totalCount);
        } catch (TableStoreException e) {
            throw translate(e, "Search: table=" + table + ", index=" + indexName);
        }
    }

    /**
     * offset 模式适配（评审修复 Major-4）：以 token 循环翻页跳过前 offset 行后取 limit 行。
     *
     * <p>逐页以 {@link QuerySpec#builderFrom(QuerySpec)} 复制条件/排序构造，首页携带
     * getTotalCount；offset 模式不返回 nextToken（与调用方语义对齐：深翻一律 token 模式）。</p>
     *
     * @param table     表名
     * @param indexName 多元索引名
     * @param spec      查询规格（offset>0）
     * @return 偏移后的结果页（nextToken 恒为 null）
     */
    private SearchResult searchWithOffset(String table, String indexName, QuerySpec spec) {
        long offset = spec.getOffset();
        int limit = spec.getLimit();
        List<TsRow> collected = new ArrayList<>();
        String token = null;
        long skipped = 0L;
        long totalCount = SearchResult.TOTAL_COUNT_NOT_REQUESTED;
        boolean firstPage = true;
        try {
            while (collected.size() < limit) {
                long remainingSkip = Math.max(0L, offset - skipped);
                int remainingTake = limit - collected.size();
                int pageLimit = (int) Math.min(QuerySpec.MAX_LIMIT, remainingSkip + remainingTake);
                if (pageLimit <= 0) {
                    break;
                }
                QuerySpec pageSpec = QuerySpec.builderFrom(spec)
                        .limit(pageLimit)
                        .nextToken(token)
                        .getTotalCount(firstPage && spec.isGetTotalCount())
                        .build();
                SearchResponse response = client.search(buildSearchRequest(table, indexName, pageSpec));
                if (firstPage && spec.isGetTotalCount()) {
                    totalCount = response.getTotalCount();
                }
                List<Row> pageRows = response.getRows();
                for (Row row : pageRows) {
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    collected.add(OtsTypes.fromSdkRow(row));
                }
                firstPage = false;
                token = OtsQueryTranslator.encodeToken(response.getNextToken());
                if (token == null || pageRows.isEmpty()) {
                    break;
                }
            }
            return new SearchResult(collected, null, totalCount);
        } catch (TableStoreException e) {
            throw translate(e, "Search(offset): table=" + table + ", index=" + indexName);
        }
    }

    /** 构造 SearchRequest（ReturnAll 列返回，检索/offset 循环翻页共用） */
    private static SearchRequest buildSearchRequest(String table, String indexName, QuerySpec spec) {
        SearchRequest request = new SearchRequest(table, indexName,
                OtsQueryTranslator.translate(spec));
        SearchRequest.ColumnsToGet columnsToGet = new SearchRequest.ColumnsToGet();
        columnsToGet.setReturnAll(true);
        request.setColumnsToGet(columnsToGet);
        return request;
    }

    // ==================== 表管理 ====================

    @Override
    public void ensureTable(TableDefinition definition) {
        getMetadataRegistry().register(definition);
        try {
            Set<String> existing = client.listTable().getTableNames().stream()
                    .collect(Collectors.toSet());
            if (existing.contains(definition.getName())) {
                return;
            }
            TableMeta meta = new TableMeta(definition.getName());
            for (PkColumnDef pk : definition.getPrimaryKey()) {
                meta.addPrimaryKeyColumn(new PrimaryKeySchema(pk.getName(),
                        PkColumnDef.TYPE_INTEGER.equals(pk.getType())
                                ? PrimaryKeyType.INTEGER : PrimaryKeyType.STRING));
            }
            TableOptions options = new TableOptions();
            options.setTimeToLive((int) definition.getTtlSeconds());
            options.setMaxVersions(definition.getMaxVersions());
            CreateTableRequest request = new CreateTableRequest(meta, options);
            if (definition.isLocalTxn()) {
                request.setLocalTxnEnabled(true);
            }
            client.createTable(request);
            log.info("[tablestore][bootstrap] 建表成功: {}", definition.getName());
        } catch (TableStoreException e) {
            // 并发启动竞争建表：已存在即幂等成功
            if ("OTSObjectAlreadyExist".equals(e.getErrorCode())) {
                return;
            }
            throw translate(e, "CreateTable: table=" + definition.getName());
        }
    }

    @Override
    public void ensureSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        try {
            if (searchIndexExists(tableName, indexDefinition.getName())) {
                return;
            }
            doCreateSearchIndex(tableName, indexDefinition);
        } catch (TableStoreException e) {
            if ("OTSObjectAlreadyExist".equals(e.getErrorCode())) {
                return;
            }
            throw translate(e, "CreateSearchIndex: table=" + tableName
                    + ", index=" + indexDefinition.getName());
        }
    }

    /**
     * 多元索引字段对账注册（任务 #7：Nested 子字段动态注册落地真实 OTS）。
     *
     * <p>流程：索引不存在 → 直接创建（含全部子字段）；存在 → DescribeSearchIndex
     * 取现有 schema 求差集，期望字段（含 Nested 子字段）全覆盖则跳过；缺失则
     * DeleteSearchIndex + CreateSearchIndex 重建。</p>
     *
     * <p><b>权衡说明</b>：tablestore SDK 5.17.4 的 UpdateSearchIndex 仅支持
     * TTL / 查询流量权重 / switchIndexName，不支持增量追加字段，重建是唯一
     * 落地路径；重建窗口内该索引检索不可用（测试/预发实例可接受）。<b>生产
     * 建议（本期不实现，留运维路径）</b>：以新索引名 CreateSearchIndex 全量定义
     * → 同步完成后 UpdateSearchIndex.setSwitchIndexName 切流 → 再删旧索引，
     * 可避免检索空窗。</p>
     */
    @Override
    public void reconcileSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        try {
            if (!searchIndexExists(tableName, indexDefinition.getName())) {
                doCreateSearchIndex(tableName, indexDefinition);
                return;
            }
            DescribeSearchIndexRequest describeRequest = new DescribeSearchIndexRequest();
            describeRequest.setTableName(tableName);
            describeRequest.setIndexName(indexDefinition.getName());
            DescribeSearchIndexResponse describeResponse = client.describeSearchIndex(describeRequest);
            if (isSchemaCovered(describeResponse.getSchema(), indexDefinition)) {
                log.info("[tablestore][index-registrar] 索引字段已覆盖，跳过重建: {}.{}",
                        tableName, indexDefinition.getName());
                return;
            }
            log.warn("[tablestore][index-registrar] 既有索引缺失字段（Nested 子字段未注册等），"
                    + "执行删除重建（重建窗口内检索不可用；生产建议新建异名索引后"
                    + " UpdateSearchIndex.switchIndexName 切流）: {}.{}",
                    tableName, indexDefinition.getName());
            DeleteSearchIndexRequest deleteRequest = new DeleteSearchIndexRequest();
            deleteRequest.setTableName(tableName);
            deleteRequest.setIndexName(indexDefinition.getName());
            client.deleteSearchIndex(deleteRequest);
            // 评审项 2：delete 成功后 create 失败会导致索引真空至下次发布，
            // 就地退避重试 3 次（200/400/600ms），OTSObjectAlreadyExist 视为成功
            createIndexAfterDeleteWithRetry(tableName, indexDefinition);
        } catch (TableStoreException e) {
            if ("OTSObjectAlreadyExist".equals(e.getErrorCode())) {
                return;
            }
            throw translate(e, "ReconcileSearchIndex: table=" + tableName
                    + ", index=" + indexDefinition.getName());
        }
    }

    /** delete 后的重建退避重试（200/400/600ms）：重试耗尽打 ERROR 并抛出（检索真空需人工介入） */
    private void createIndexAfterDeleteWithRetry(String tableName, SearchIndexDef indexDefinition) {
        long[] backoffMs = {200L, 400L, 600L};
        TableStoreException lastError = null;
        for (int attempt = 0; attempt <= backoffMs.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(backoffMs[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TablestoreAccessException("索引重建退避等待被中断", ie);
                }
            }
            try {
                doCreateSearchIndex(tableName, indexDefinition);
                return;
            } catch (TableStoreException e) {
                if ("OTSObjectAlreadyExist".equals(e.getErrorCode())) {
                    // 并发竞争建表/建索引：已存在即幂等成功
                    return;
                }
                lastError = e;
                log.warn("[tablestore][index-registrar] 删除后重建索引失败，退避重试 {}/{}: {}.{}",
                        attempt, backoffMs.length, tableName, indexDefinition.getName(), e);
            }
        }
        log.error("[tablestore][index-registrar] 索引已删除但重建失败，检索真空，需人工介入: {}.{}",
                tableName, indexDefinition.getName(), lastError);
        throw translate(lastError, "ReconcileSearchIndex(recreate): table=" + tableName
                + ", index=" + indexDefinition.getName());
    }

    @Override
    public com.example.common.infrastructure.tablestore.metadata.SearchIndexState describeSearchIndex(
            String tableName, String indexName) {
        try {
            DescribeSearchIndexRequest request = new DescribeSearchIndexRequest();
            request.setTableName(tableName);
            request.setIndexName(indexName);
            DescribeSearchIndexResponse response = client.describeSearchIndex(request);
            String status = response.getIndexStatus() == null
                    || response.getIndexStatus().indexStatusEnum == null
                    ? "UNKNOWN" : response.getIndexStatus().indexStatusEnum.status();
            SyncStat sync = response.getSyncStat();
            String phase = sync == null || sync.getSyncPhase() == null
                    ? null : sync.getSyncPhase().name();
            Long syncTimestamp = sync == null ? null : sync.getCurrentSyncTimestamp();
            return new com.example.common.infrastructure.tablestore.metadata.SearchIndexState(
                    indexName, true, status, phase, syncTimestamp,
                    response.getBrotherIndexName());
        } catch (TableStoreException e) {
            if ("OTSObjectNotExist".equals(e.getErrorCode())
                    || "OTSObjectNotExist".equalsIgnoreCase(e.getErrorCode())) {
                return new com.example.common.infrastructure.tablestore.metadata.SearchIndexState(
                        indexName, false, null, null, null, null);
            }
            throw translate(e, "DescribeSearchIndex: table=" + tableName
                    + ", index=" + indexName);
        }
    }

    @Override
    public boolean isSearchIndexCovered(String tableName, SearchIndexDef indexDefinition) {
        if (!searchIndexExists(tableName, indexDefinition.getName())) {
            return false;
        }
        DescribeSearchIndexRequest request = new DescribeSearchIndexRequest();
        request.setTableName(tableName);
        request.setIndexName(indexDefinition.getName());
        try {
            return isSchemaCovered(client.describeSearchIndex(request).getSchema(), indexDefinition);
        } catch (TableStoreException e) {
            if ("OTSObjectNotExist".equals(e.getErrorCode())) {
                return false;
            }
            throw translate(e, "DescribeSearchIndex(coverage): table=" + tableName
                    + ", index=" + indexDefinition.getName());
        }
    }

    @Override
    public void createSearchIndexIfAbsent(String tableName, SearchIndexDef indexDefinition) {
        ensureSearchIndex(tableName, indexDefinition);
    }

    @Override
    public void createBrotherSearchIndex(String tableName, String sourceIndex,
                                         SearchIndexDef candidateDefinition) {
        try {
            if (searchIndexExists(tableName, candidateDefinition.getName())) {
                return;
            }
            doCreateSearchIndex(tableName, candidateDefinition, sourceIndex);
        } catch (TableStoreException e) {
            if ("OTSObjectAlreadyExist".equals(e.getErrorCode())) {
                return;
            }
            throw translate(e, "CreateSearchIndex(brother): table=" + tableName
                    + ", source=" + sourceIndex + ", candidate=" + candidateDefinition.getName());
        }
    }

    @Override
    public void switchSearchIndex(String tableName, String logicalIndex, String candidateIndex) {
        try {
            UpdateSearchIndexRequest request = new UpdateSearchIndexRequest(tableName, logicalIndex);
            request.setSwitchIndexName(candidateIndex);
            client.updateSearchIndex(request);
            log.info("[tablestore][index-switch] 已切换索引路由: {}.{} -> {}",
                    tableName, logicalIndex, candidateIndex);
        } catch (TableStoreException e) {
            throw translate(e, "UpdateSearchIndex(switch): table=" + tableName
                    + ", logical=" + logicalIndex + ", candidate=" + candidateIndex);
        }
    }

    @Override
    public void deleteSearchIndexIfExists(String tableName, String indexName) {
        if (!searchIndexExists(tableName, indexName)) {
            return;
        }
        try {
            DeleteSearchIndexRequest request = new DeleteSearchIndexRequest();
            request.setTableName(tableName);
            request.setIndexName(indexName);
            client.deleteSearchIndex(request);
            log.info("[tablestore][index-cleanup] 已删除索引: {}.{}", tableName, indexName);
        } catch (TableStoreException e) {
            if ("OTSObjectNotExist".equals(e.getErrorCode())) {
                return;
            }
            throw translate(e, "DeleteSearchIndex: table=" + tableName + ", index=" + indexName);
        }
    }

    /** 索引存在性判定（ListSearchIndex 按名匹配） */
    private boolean searchIndexExists(String tableName, String indexName) {
        ListSearchIndexRequest listRequest = new ListSearchIndexRequest();
        listRequest.setTableName(tableName);
        ListSearchIndexResponse listResponse = client.listSearchIndex(listRequest);
        return listResponse.getIndexInfos().stream()
                .map(SearchIndexInfo::getIndexName)
                .anyMatch(indexName::equals);
    }

    /**
     * 创建多元索引（ensure/reconcile 共用）：索引 TTL 对齐表 TTL。
     *
     * <p>OTS 约束「索引 TTL ≤ 表 TTL」，不设则默认 forever，
     * TTL 表（如 audit_log/event_outbox）会因索引 forever 被拒（OTSParameterInvalid）。</p>
     */
    private void doCreateSearchIndex(String tableName, SearchIndexDef indexDefinition) {
        doCreateSearchIndex(tableName, indexDefinition, null);
    }

    private void doCreateSearchIndex(String tableName, SearchIndexDef indexDefinition,
                                     String sourceIndexName) {
        CreateSearchIndexRequest request = new CreateSearchIndexRequest();
        request.setTableName(tableName);
        request.setIndexName(indexDefinition.getName());
        IndexSchema schema = new IndexSchema();
        schema.setFieldSchemas(indexDefinition.getFields().stream()
                .map(OtsTablestoreTemplate::toFieldSchema)
                .collect(Collectors.toList()));
        request.setIndexSchema(schema);
        if (sourceIndexName != null && !sourceIndexName.isBlank()) {
            // 只有 sourceIndexName 创建的兄弟索引才能被 switchIndexName 切流
            request.setSourceIndexName(sourceIndexName);
        }
        long tableTtlSeconds = getMetadataRegistry().get(tableName)
                .map(TableDefinition::getTtlSeconds)
                .orElse(TableDefinition.TTL_FOREVER);
        if (tableTtlSeconds > 0) {
            request.setTimeToLive((int) tableTtlSeconds, TimeUnit.SECONDS);
        }
        client.createSearchIndex(request);
        log.info("[tablestore][bootstrap] 建多元索引成功: {}.{}{}（索引TTL={}）", tableName,
                indexDefinition.getName(),
                sourceIndexName == null ? "" : "(brother of " + sourceIndexName + ")",
                tableTtlSeconds > 0 ? tableTtlSeconds + "s" : "forever");
    }

    /**
     * 期望定义覆盖度判定：逐字段按名称 + 类型比对；Nested 容器要求现有
     * 子字段名集合 ⊇ 期望子字段名集合，且逐子字段比对类型（评审项 5：
     * 类型漂移视为未覆盖触发重建）；现有额外子字段不视为缺失（兼容历史注册）。
     *
     * @param existing        DescribeSearchIndex 返回的现有 schema（可为 null）
     * @param indexDefinition 期望的完整索引定义
     * @return 全覆盖返回 true（无需重建）
     */
    static boolean isSchemaCovered(IndexSchema existing, SearchIndexDef indexDefinition) {
        if (existing == null || existing.getFieldSchemas() == null) {
            return false;
        }
        Map<String, FieldSchema> existingByName = new HashMap<>();
        for (FieldSchema field : existing.getFieldSchemas()) {
            existingByName.put(field.getFieldName(), field);
        }
        for (IndexFieldDef def : indexDefinition.getFields()) {
            FieldSchema field = existingByName.get(def.getName());
            if (field == null || field.getFieldType() != toFieldType(def.getType())) {
                return false;
            }
            List<IndexFieldDef> desiredSubs = def.getSubFields();
            if (desiredSubs == null || desiredSubs.isEmpty()) {
                continue;
            }
            Map<String, FieldSchema> existingSubs = new HashMap<>();
            if (field.getSubFieldSchemas() != null) {
                for (FieldSchema sub : field.getSubFieldSchemas()) {
                    existingSubs.put(sub.getFieldName(), sub);
                }
            }
            for (IndexFieldDef sub : desiredSubs) {
                FieldSchema existingSub = existingSubs.get(sub.getName());
                // 名字 + 类型双比对：缺失或类型漂移均视为未覆盖
                if (existingSub == null
                        || existingSub.getFieldType() != toFieldType(sub.getType())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static FieldSchema toFieldSchema(IndexFieldDef def) {
        FieldSchema schema = new FieldSchema(def.getName(), toFieldType(def.getType()));
        if (def.isArray()) {
            schema.setIsArray(true);
        }
        if (def.getSubFields() != null && !def.getSubFields().isEmpty()) {
            schema.setSubFieldSchemas(def.getSubFields().stream()
                    .map(OtsTablestoreTemplate::toFieldSchema)
                    .collect(Collectors.toList()));
        }
        return schema;
    }

    private static FieldType toFieldType(String type) {
        switch (type) {
            case IndexFieldDef.TYPE_KEYWORD:
                return FieldType.KEYWORD;
            case IndexFieldDef.TYPE_LONG:
                return FieldType.LONG;
            case IndexFieldDef.TYPE_DOUBLE:
                return FieldType.DOUBLE;
            case IndexFieldDef.TYPE_BOOLEAN:
                return FieldType.BOOLEAN;
            case IndexFieldDef.TYPE_TEXT:
                return FieldType.TEXT;
            case IndexFieldDef.TYPE_NESTED:
                return FieldType.NESTED;
            default:
                throw new IllegalArgumentException("不支持的索引字段类型: " + type);
        }
    }

    // ==================== 异常语义转换 ====================

    /**
     * SDK 异常 → 防腐层异常（供上层区分 409 与退避重试）。
     *
     * @param e       SDK 异常
     * @param context 操作上下文描述
     * @return 防腐层异常（不抛出，由调用方 throw）
     */
    static RuntimeException translate(TableStoreException e, String context) {
        String errorCode = e.getErrorCode();
        if ("OTSConditionCheckFail".equals(errorCode)) {
            return new TsConditionCheckException("条件更新失败: " + context, e);
        }
        if ("OTSRowOperationConflict".equals(errorCode)) {
            return new TsOperationConflictException("行操作冲突: " + context, e);
        }
        return new TablestoreAccessException("Tablestore 访问失败[" + errorCode + "]: " + context, e);
    }
}
