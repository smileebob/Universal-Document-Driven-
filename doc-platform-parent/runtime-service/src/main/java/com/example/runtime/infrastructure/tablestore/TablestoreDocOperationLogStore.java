package com.example.runtime.infrastructure.tablestore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocOperationLogStore;

/**
 * 操作日志读取与投影状态推进（doc_data 同表日志行）。
 *
 * <p>待处理扫描依赖 V2 新增的 {@code doc_data_operation_index}；业务查询在
 * doc_data_index 上强制 deleted=0，日志行以 deleted=1 隔离，不会被渲染为单据。</p>
 */
@Component
public class TablestoreDocOperationLogStore implements DocOperationLogStore {

    static final String TABLE = "doc_data";
    static final String INDEX = "doc_data_operation_index";

    private static final String EMPTY_OWNER = "";

    private final TablestoreTemplate template;

    public TablestoreDocOperationLogStore(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<DocOperationLog> find(String partitionKey, String logUuid) {
        return template.getRow(TABLE, pK(partitionKey, logUuid)).map(this::mapRow);
    }

    @Override
    public ScanPage scanPending(long now, int limit, String nextToken) {
        QuerySpec.Builder builder = QuerySpec.builder()
                .term("record_kind", TsColumnValue.of(DocOperationLog.RECORD_KIND_OPERATION))
                .term("projection_status", TsColumnValue.of(DocOperationLog.PROJECTION_PENDING))
                .range("next_projection_time", null, true, TsColumnValue.of(now), true)
                .sortAsc("occurred_at")
                .limit(Math.min(Math.max(1, limit), QuerySpec.MAX_LIMIT));
        if (nextToken != null && !nextToken.isEmpty()) {
            builder.nextToken(nextToken);
        }
        SearchResult result = template.search(TABLE, INDEX, builder.build());
        List<DocOperationLog> logs = new ArrayList<>(result.getRows().size());
        for (TsRow row : result.getRows()) {
            logs.add(mapRow(row));
        }
        return new ScanPage(logs, result.getNextToken());
    }

    @Override
    public boolean claim(DocOperationLog log, String owner, long now, long leaseUntil) {
        try {
            template.updateRow(TABLE, pK(log.getPartitionKey(), log.getLogUuid()),
                    List.of(
                            ColumnUpdate.put("projection_lease_owner", TsColumnValue.of(owner)),
                            ColumnUpdate.put("projection_lease_until", TsColumnValue.of(leaseUntil)),
                            ColumnUpdate.put("next_projection_time", TsColumnValue.of(leaseUntil)),
                            ColumnUpdate.put("projection_attempts",
                                    TsColumnValue.of(log.getProjectionAttempts() + 1))),
                    RowCondition.expectExistAndAllEq(Map.of(
                            "projection_status",
                            TsColumnValue.of(DocOperationLog.PROJECTION_PENDING),
                            "projection_lease_owner",
                            TsColumnValue.of(nullToEmpty(log.getProjectionLeaseOwner())))));
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    @Override
    public void markProjected(DocOperationLog log, String owner) {
        template.updateRow(TABLE, pK(log.getPartitionKey(), log.getLogUuid()),
                List.of(
                        ColumnUpdate.put("projection_status",
                                TsColumnValue.of(DocOperationLog.PROJECTION_DONE)),
                        ColumnUpdate.put("projection_lease_owner", TsColumnValue.of(EMPTY_OWNER)),
                        ColumnUpdate.put("projection_lease_until", TsColumnValue.of(0L)),
                        ColumnUpdate.put("next_projection_time", TsColumnValue.of(0L)),
                        ColumnUpdate.put("last_projection_error", TsColumnValue.of(""))),
                RowCondition.expectExistAndAllEq(Map.of(
                        "projection_status", TsColumnValue.of(DocOperationLog.PROJECTION_PENDING),
                        "projection_lease_owner", TsColumnValue.of(owner))));
    }

    @Override
    public void markProjectionFailure(DocOperationLog log, String owner, String error,
                                      long nextTime, boolean manual) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put("projection_status", TsColumnValue.of(
                manual ? DocOperationLog.PROJECTION_MANUAL : DocOperationLog.PROJECTION_PENDING)));
        updates.add(ColumnUpdate.put("projection_lease_owner", TsColumnValue.of(EMPTY_OWNER)));
        updates.add(ColumnUpdate.put("projection_lease_until", TsColumnValue.of(0L)));
        updates.add(ColumnUpdate.put("next_projection_time", TsColumnValue.of(nextTime)));
        updates.add(ColumnUpdate.put("last_projection_error", TsColumnValue.of(
                error == null ? "" : truncate(error, 500))));
        try {
            template.updateRow(TABLE, pK(log.getPartitionKey(), log.getLogUuid()), updates,
                    RowCondition.expectExistAndAllEq(Map.of(
                            "projection_status",
                            TsColumnValue.of(DocOperationLog.PROJECTION_PENDING),
                            "projection_lease_owner", TsColumnValue.of(owner))));
        } catch (TsConditionCheckException e) {
            // 已被其他实例完成/接管；保留既有结果
        }
    }

    @Override
    public List<DocOperationLog> scanByDoc(String docCode, String docUuid, long fromTime,
                                           long toTime, int limit) {
        // 重放走同分区 GetRange 强一致读取，不依赖多元索引可见性；同分区可能
        // 共享 hash4 的其他单据行按 doc_uuid + record_kind 过滤。
        String partitionKey = com.example.common.infrastructure.tablestore.util.PartitionKeys
                .dataKey(docCode, docUuid);
        List<DocOperationLog> logs = new ArrayList<>();
        for (TsRow row : template.getPartitionRange(TABLE, partitionKey)) {
            Long kind = row.getLong("record_kind");
            if (kind == null || kind != DocOperationLog.RECORD_KIND_OPERATION) {
                continue;
            }
            if (!docUuid.equals(row.getString("doc_uuid"))) {
                continue;
            }
            long occurredAt = longOf(row, "occurred_at");
            if (occurredAt < fromTime || occurredAt > toTime) {
                continue;
            }
            logs.add(mapRow(row));
        }
        logs.sort(java.util.Comparator.comparingLong(DocOperationLog::getOccurredAt));
        int safeLimit = Math.min(Math.max(1, limit), 1000);
        return logs.size() <= safeLimit ? logs : logs.subList(0, safeLimit);
    }

    @Override
    public long countPending(long now) {
        QuerySpec spec = QuerySpec.builder()
                .term("record_kind", TsColumnValue.of(DocOperationLog.RECORD_KIND_OPERATION))
                .term("projection_status", TsColumnValue.of(DocOperationLog.PROJECTION_PENDING))
                .range("next_projection_time", null, true, TsColumnValue.of(now), true)
                .limit(1)
                .getTotalCount(true)
                .build();
        return template.search(TABLE, INDEX, spec).getTotalCount();
    }

    @Override
    public ScanPage scanProjected(long before, int limit, String nextToken) {
        QuerySpec.Builder builder = QuerySpec.builder()
                .term("record_kind", TsColumnValue.of(DocOperationLog.RECORD_KIND_OPERATION))
                .term("projection_status", TsColumnValue.of(DocOperationLog.PROJECTION_DONE))
                .range("occurred_at", null, true, TsColumnValue.of(before), true)
                .sortAsc("occurred_at")
                .limit(Math.min(Math.max(1, limit), QuerySpec.MAX_LIMIT));
        if (nextToken != null && !nextToken.isEmpty()) {
            builder.nextToken(nextToken);
        }
        SearchResult result = template.search(TABLE, INDEX, builder.build());
        List<DocOperationLog> logs = new ArrayList<>(result.getRows().size());
        for (TsRow row : result.getRows()) {
            logs.add(mapRow(row));
        }
        return new ScanPage(logs, result.getNextToken());
    }

    @Override
    public boolean deleteProjected(DocOperationLog log) {
        if (log.getProjectionStatus() != DocOperationLog.PROJECTION_DONE) {
            return false;
        }
        try {
            template.deleteRow(TABLE, pK(log.getPartitionKey(), log.getLogUuid()),
                    RowCondition.expectExistAndColumnEq("projection_status",
                            TsColumnValue.of(DocOperationLog.PROJECTION_DONE)));
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    private DocOperationLog mapRow(TsRow row) {
        DocOperationLog log = new DocOperationLog();
        log.setPartitionKey(String.valueOf(row.getPrimaryKey().get("partition_key")));
        log.setLogUuid(String.valueOf(row.getPrimaryKey().get("uuid")));
        log.setOperationId(row.getString("operation_id"));
        log.setDocCode(row.getString("doc_code"));
        log.setDocUuid(row.getString("doc_uuid"));
        log.setOperation(row.getString("operation"));
        log.setEvent(row.getString("event"));
        log.setSchemaVersion(longOf(row, "schema_version"));
        log.setRequestDigest(row.getString("request_digest"));
        log.setExpectedVersion(longOf(row, "expected_version"));
        log.setVersionAfter(longOf(row, "version_after"));
        log.setFromStatus(nullableLong(row, "from_status"));
        log.setToStatus(nullableLong(row, "to_status"));
        log.setEventId(row.getString("event_id"));
        log.setEventType(row.getString("event_type"));
        log.setEventSeq(nullableLong(row, "event_seq"));
        log.setPreviousEventSeq(nullableLong(row, "previous_event_seq"));
        log.setHistoryJson(row.getString("history_json"));
        log.setAuditJson(row.getString("audit_json"));
        log.setEventJson(row.getString("event_json"));
        log.setSagaId(row.getString("saga_id"));
        log.setOperator(row.getString("operator"));
        log.setTraceId(row.getString("trace_id"));
        log.setOccurredAt(longOf(row, "occurred_at"));
        log.setProjectionStatus(longOf(row, "projection_status"));
        log.setProjectionAttempts(longOf(row, "projection_attempts"));
        log.setNextProjectionTime(longOf(row, "next_projection_time"));
        log.setProjectionLeaseOwner(row.getString("projection_lease_owner"));
        log.setProjectionLeaseUntil(longOf(row, "projection_lease_until"));
        log.setLastProjectionError(row.getString("last_projection_error"));
        log.setCreateTime(longOf(row, "create_time"));
        return log;
    }

    private static TsPrimaryKey pK(String partitionKey, String logUuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("uuid", logUuid)
                .build();
    }

    private static long longOf(TsRow row, String column) {
        Long value = row.getLong(column);
        return value == null ? 0L : value;
    }

    private static Long nullableLong(TsRow row, String column) {
        return row.getLong(column);
    }

    private static String nullToEmpty(String value) {
        return value == null ? EMPTY_OWNER : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
