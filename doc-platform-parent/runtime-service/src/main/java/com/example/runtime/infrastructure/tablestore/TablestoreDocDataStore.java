package com.example.runtime.infrastructure.tablestore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocOperationContext;
import com.example.runtime.domain.port.DocDataStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据数据存储 Tablestore 实现。
 *
 * <p><b>同一局部事务</b>：OTS 局部事务为表级作用域，但同表同分区可写多行。
 * 主行业务变更与 {@code uuid=op:{docUuid}:{operationId}} 的不可变操作日志行
 * 在同一事务提交；history/outbox/audit 由日志投影任务幂等重建（03 §4）。</p>
 *
 * <p>日志行以 {@code record_kind=1} + {@code deleted=1} 隔离，现有业务检索
 * 强制 deleted=0，不会把内部日志渲染成单据。</p>
 */
@Component
public class TablestoreDocDataStore implements DocDataStore {

    static final String TABLE_DOC_DATA = "doc_data";

    private static final String ENVELOPE_SCHEMA_VERSION = "1.1";
    private static final String EMPTY = "";

    private static final Logger log = LoggerFactory.getLogger(TablestoreDocDataStore.class);

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public TablestoreDocDataStore(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    // ==================== 读路径 ====================

    @Override
    public Optional<DocDataRecord> find(String docCode, String uuid) {
        return template.getRow(TABLE_DOC_DATA, dataPk(docCode, uuid))
                .filter(this::isBusinessRow)
                .filter(row -> {
                    Long deleted = row.getLong("deleted");
                    return deleted == null || deleted == DocDataRecord.ACTIVE;
                })
                .map(this::mapRow);
    }

    @Override
    public List<Map<String, Object>> findHistory(String docCode, String uuid) {
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        return template.getPartitionRange("doc_data_history", partitionKey).stream()
                .filter(row -> uuid.equals(String.valueOf(row.getPrimaryKey().get("uuid"))))
                .sorted(Comparator.comparingLong(TablestoreDocDataStore::seqOf))
                .map(this::mapHistoryRow)
                .toList();
    }

    private static long seqOf(TsRow row) {
        return row.getPrimaryKey().get("seq") instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> mapHistoryRow(TsRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", seqOf(row));
        view.put("from_status", row.getLong("from_status"));
        view.put("to_status", row.getLong("to_status"));
        view.put("operation", row.getString("operation"));
        view.put("operator", row.getString("operator"));
        view.put("operate_time", row.getLong("operate_time"));
        view.put("version_after", row.getLong("version_after"));
        return view;
    }

    // ==================== 创建（主行 + 日志行同事务） ====================

    @Override
    public void create(DocDataRecord record, DocOperationContext context) {
        requireVersion(context, record.getVersion() - 1);
        String partitionKey = PartitionKeys.dataKey(record.getDocCode(), record.getUuid());
        long eventSeq = 0L;
        String eventId = null;
        if (context.hasEvent()) {
            eventSeq = 1L;
            eventId = eventId(context.operationId());
        }
        DocOperationLog logRow = buildLog(record.getDocCode(), record.getUuid(), partitionKey,
                context, null, record.getCurrentStatus(), eventId, eventSeq, 0L);
        long finalEventSeq = eventSeq;
        String finalEventId = eventId;
        template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
            Map<String, TsColumnValue> columns = new LinkedHashMap<>();
            columns.put("record_kind", TsColumnValue.of(DocOperationLog.RECORD_KIND_DOC));
            columns.put("doc_code", TsColumnValue.of(record.getDocCode()));
            columns.put("current_status", TsColumnValue.of(record.getCurrentStatus()));
            columns.put("version", TsColumnValue.of(record.getVersion()));
            columns.put("attributes", TsColumnValue.of(toJson(record.getAttributes())));
            columns.put("relations", TsColumnValue.of(toJson(record.getRelations())));
            columns.put("deleted", TsColumnValue.of(DocDataRecord.ACTIVE));
            columns.put("last_event_seq", TsColumnValue.of(finalEventSeq));
            columns.put("inflight_operation_id", TsColumnValue.of(EMPTY));
            if (record.getCreator() != null) {
                columns.put("creator", TsColumnValue.of(record.getCreator()));
            }
            if (record.getCreateDept() != null) {
                columns.put("create_dept", TsColumnValue.of(record.getCreateDept()));
            }
            columns.put("create_time", TsColumnValue.of(record.getCreateTime()));
            columns.put("update_time", TsColumnValue.of(record.getUpdateTime()));
            ops.putRow(TABLE_DOC_DATA, new TsRow(
                    dataPk(record.getDocCode(), record.getUuid()), columns),
                    RowCondition.expectNotExist());
            ops.putRow(TABLE_DOC_DATA, toRow(logRow), RowCondition.expectNotExist());
            return null;
        });
        log.debug("[doc-log] 创建与操作日志已原子提交: {}/{}, operationId={}, eventId={}",
                record.getDocCode(), record.getUuid(), context.operationId(), finalEventId);
    }

    // ==================== 编辑 / 逻辑删（主行 + 日志行同事务） ====================

    @Override
    public boolean updateEditable(String docCode, String uuid, long expectVersion,
                                  long expectStatus, DocOperationContext context) {
        requireVersion(context, expectVersion);
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        try {
            template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
                TsRow main = requireWritableMain(ops, docCode, uuid, expectVersion, expectStatus);
                long previousSeq = longOf(main, "last_event_seq");
                // 非事件操作不得占用 event_seq（event_seq 只描述事件顺序）
                long eventSeq = context.hasEvent() ? previousSeq + 1 : 0L;
                String eventId = context.hasEvent() ? eventId(context.operationId()) : null;
                List<ColumnUpdate> updates = new java.util.ArrayList<>();
                updates.add(ColumnUpdate.put("attributes",
                        TsColumnValue.of(toJson(context.afterAttributes()))));
                updates.add(ColumnUpdate.put("relations",
                        TsColumnValue.of(toJson(context.afterRelations()))));
                updates.add(ColumnUpdate.put("update_time",
                        TsColumnValue.of(context.occurredAt())));
                updates.add(ColumnUpdate.put("version",
                        TsColumnValue.of(expectVersion + 1)));
                if (context.hasEvent()) {
                    updates.add(ColumnUpdate.put("last_event_seq",
                            TsColumnValue.of(eventSeq)));
                }
                ops.updateRow(TABLE_DOC_DATA, dataPk(docCode, uuid), updates,
                        versionStatusCondition(expectVersion, expectStatus));
                DocOperationLog logRow = buildLog(docCode, uuid, partitionKey, context,
                        expectStatus, expectStatus, eventId, eventSeq,
                        context.hasEvent() ? previousSeq : 0L);
                ops.putRow(TABLE_DOC_DATA, toRow(logRow), RowCondition.expectNotExist());
                return null;
            });
            return true;
        } catch (TsConditionCheckException | DocTransitionAbortException e) {
            return false;
        }
    }

    @Override
    public boolean logicalDelete(String docCode, String uuid, long expectVersion,
                                 long expectStatus, DocOperationContext context) {
        requireVersion(context, expectVersion);
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        try {
            template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
                requireWritableMain(ops, docCode, uuid, expectVersion, expectStatus);
                List<ColumnUpdate> updates = List.of(
                        ColumnUpdate.put("deleted", TsColumnValue.of(DocDataRecord.DELETED)),
                        ColumnUpdate.put("update_time",
                                TsColumnValue.of(context.occurredAt())),
                        ColumnUpdate.put("version", TsColumnValue.of(expectVersion + 1)));
                ops.updateRow(TABLE_DOC_DATA, dataPk(docCode, uuid), updates,
                        versionStatusCondition(expectVersion, expectStatus));
                DocOperationLog logRow = buildLog(docCode, uuid, partitionKey, context,
                        expectStatus, expectStatus, null, 0L, 0L);
                ops.putRow(TABLE_DOC_DATA, toRow(logRow), RowCondition.expectNotExist());
                return null;
            });
            return true;
        } catch (TsConditionCheckException | DocTransitionAbortException e) {
            return false;
        }
    }

    // ==================== 流转收口 ====================

    @Override
    public TransitionOutcome commitTransition(String docCode, String uuid, long expectedVersion,
                                              long fromStatus, long toStatus,
                                              DocOperationContext context) {
        requireVersion(context, expectedVersion);
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        return template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
            TsRow main = requireTransitionMain(ops, docCode, uuid, expectedVersion, fromStatus,
                    context.operationId());
            long previousSeq = longOf(main, "last_event_seq");
            long eventSeq = context.hasEvent() ? previousSeq + 1 : previousSeq;
            String eventId = context.hasEvent() ? eventId(context.operationId()) : null;
            long newVersion = expectedVersion + 1;
            List<ColumnUpdate> updates = new java.util.ArrayList<>();
            updates.add(ColumnUpdate.put("current_status", TsColumnValue.of(toStatus)));
            updates.add(ColumnUpdate.put("version", TsColumnValue.of(newVersion)));
            updates.add(ColumnUpdate.put("update_time", TsColumnValue.of(context.occurredAt())));
            updates.add(ColumnUpdate.put("inflight_operation_id", TsColumnValue.of(EMPTY)));
            updates.add(ColumnUpdate.put("inflight_saga_id", TsColumnValue.of(EMPTY)));
            updates.add(ColumnUpdate.put("inflight_lease_until", TsColumnValue.of(0L)));
            if (context.hasEvent()) {
                updates.add(ColumnUpdate.put("last_event_seq", TsColumnValue.of(eventSeq)));
            }
            ops.updateRow(TABLE_DOC_DATA, dataPk(docCode, uuid), updates,
                    versionStatusCondition(expectedVersion, fromStatus));
            DocOperationLog logRow = buildLog(docCode, uuid, partitionKey, context,
                    fromStatus, toStatus, eventId, eventSeq, previousSeq);
            ops.putRow(TABLE_DOC_DATA, toRow(logRow), RowCondition.expectNotExist());
            return new TransitionOutcome(newVersion, newVersion, eventId, eventSeq);
        });
    }

    // ==================== 在途操作锁 ====================

    @Override
    public long acquireOperation(String docCode, String uuid, long expectVersion, long expectStatus,
                                 String operationId, String sagaId, long leaseUntil) {
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        try {
            return template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
                TsRow main = ops.getRow(TABLE_DOC_DATA, dataPk(docCode, uuid))
                        .orElseThrow(() -> new DocTransitionAbortException("单据不存在: " + uuid));
                if (isDeleted(main)) {
                    throw new DocTransitionAbortException("单据已删除: " + uuid);
                }
                if (longOf(main, "version") != expectVersion
                        || longOf(main, "current_status") != expectStatus) {
                    throw new TsConditionCheckException("版本或状态已变化");
                }
                String existing = main.getString("inflight_operation_id");
                long existingLease = longOf(main, "inflight_lease_until");
                if (existing != null && !existing.isBlank()
                        && existingLease > System.currentTimeMillis()) {
                    throw new TsConditionCheckException("存在未完成的在途操作: " + existing);
                }
                long newVersion = expectVersion + 1;
                List<ColumnUpdate> updates = List.of(
                        ColumnUpdate.put("version", TsColumnValue.of(newVersion)),
                        ColumnUpdate.put("inflight_operation_id",
                                TsColumnValue.of(operationId)),
                        ColumnUpdate.put("inflight_saga_id",
                                TsColumnValue.of(sagaId == null ? EMPTY : sagaId)),
                        ColumnUpdate.put("inflight_lease_until", TsColumnValue.of(leaseUntil)),
                        ColumnUpdate.put("update_time",
                                TsColumnValue.of(System.currentTimeMillis())));
                ops.updateRow(TABLE_DOC_DATA, dataPk(docCode, uuid), updates,
                        versionStatusCondition(expectVersion, expectStatus));
                return newVersion;
            });
        } catch (TsConditionCheckException | DocTransitionAbortException e) {
            return 0L;
        }
    }

    @Override
    public boolean releaseOperation(String docCode, String uuid, String operationId,
                                    long updateTime) {
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        try {
            template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
                TsRow main = ops.getRow(TABLE_DOC_DATA, dataPk(docCode, uuid)).orElse(null);
                if (main == null || !operationId.equals(main.getString("inflight_operation_id"))) {
                    throw new TsConditionCheckException("在途操作已释放或不属于本操作");
                }
                long version = longOf(main, "version");
                ops.updateRow(TABLE_DOC_DATA, dataPk(docCode, uuid), List.of(
                                ColumnUpdate.put("version", TsColumnValue.of(version + 1)),
                                ColumnUpdate.put("inflight_operation_id", TsColumnValue.of(EMPTY)),
                                ColumnUpdate.put("inflight_saga_id", TsColumnValue.of(EMPTY)),
                                ColumnUpdate.put("inflight_lease_until", TsColumnValue.of(0L)),
                                ColumnUpdate.put("update_time", TsColumnValue.of(updateTime))),
                        RowCondition.expectExistAndAllEq(Map.of(
                                "version", TsColumnValue.of(version),
                                "inflight_operation_id", TsColumnValue.of(operationId))));
                return null;
            });
            return true;
        } catch (TsConditionCheckException | DocTransitionAbortException e) {
            return false;
        }
    }

    // ==================== 事务内校验 ====================

    private TsRow requireWritableMain(com.example.common.infrastructure.tablestore.LocalTransactionOps ops,
                                      String docCode, String uuid, long expectVersion,
                                      long expectStatus) {
        TsRow main = ops.getRow(TABLE_DOC_DATA, dataPk(docCode, uuid))
                .orElseThrow(() -> new DocTransitionAbortException("单据不存在: " + uuid));
        if (isDeleted(main)) {
            throw new DocTransitionAbortException("单据已删除: " + uuid);
        }
        String inflight = main.getString("inflight_operation_id");
        if (inflight != null && !inflight.isBlank()) {
            throw new TsConditionCheckException("存在未完成的在途操作: " + inflight);
        }
        if (longOf(main, "version") != expectVersion
                || longOf(main, "current_status") != expectStatus) {
            throw new TsConditionCheckException("版本或状态已变化");
        }
        return main;
    }

    private TsRow requireTransitionMain(com.example.common.infrastructure.tablestore.LocalTransactionOps ops,
                                        String docCode, String uuid, long expectedVersion,
                                        long fromStatus, String operationId) {
        TsRow main = ops.getRow(TABLE_DOC_DATA, dataPk(docCode, uuid))
                .orElseThrow(() -> new DocTransitionAbortException("单据不存在: " + uuid));
        if (isDeleted(main)) {
            throw new DocTransitionAbortException("单据已删除: " + uuid);
        }
        String inflight = main.getString("inflight_operation_id");
        if (inflight != null && !inflight.isBlank() && !inflight.equals(operationId)) {
            throw new TsConditionCheckException("在途操作所有权不匹配: " + inflight);
        }
        if (longOf(main, "version") != expectedVersion
                || longOf(main, "current_status") != fromStatus) {
            throw new TsConditionCheckException("版本或状态已变化");
        }
        return main;
    }

    private static RowCondition versionStatusCondition(long version, long status) {
        return RowCondition.expectExistAndAllEq(Map.of(
                "version", TsColumnValue.of(version),
                "current_status", TsColumnValue.of(status)));
    }

    private static boolean isDeleted(TsRow row) {
        Long deleted = row.getLong("deleted");
        return deleted != null && deleted == DocDataRecord.DELETED;
    }

    private static long longOf(TsRow row, String column) {
        Long value = row.getLong(column);
        return value == null ? 0L : value;
    }

    private boolean isBusinessRow(TsRow row) {
        Long kind = row.getLong("record_kind");
        return kind == null || kind == DocOperationLog.RECORD_KIND_DOC;
    }

    private static void requireVersion(DocOperationContext context, long expectVersion) {
        if (context.afterVersion() != expectVersion + 1) {
            throw new IllegalArgumentException("操作上下文 afterVersion 与期望版本不匹配: expect="
                    + expectVersion + ", after=" + context.afterVersion());
        }
    }

    // ==================== 操作日志构造 ====================

    private DocOperationLog buildLog(String docCode, String uuid, String partitionKey,
                                     DocOperationContext context, Long fromStatus, long toStatus,
                                     String eventId, long eventSeq, long previousSeq) {
        DocOperationLog logRow = new DocOperationLog();
        logRow.setPartitionKey(partitionKey);
        logRow.setLogUuid(logUuid(uuid, context.operationId()));
        logRow.setOperationId(context.operationId());
        logRow.setDocCode(docCode);
        logRow.setDocUuid(uuid);
        logRow.setOperation(context.operation());
        logRow.setEvent(context.event());
        logRow.setSchemaVersion(context.schemaVersion());
        logRow.setRequestDigest(digest(context));
        logRow.setExpectedVersion(context.beforeVersion());
        logRow.setVersionAfter(context.afterVersion());
        logRow.setFromStatus(fromStatus);
        logRow.setToStatus(toStatus);
        logRow.setEventId(eventId);
        logRow.setEventType(context.eventType());
        logRow.setEventSeq(eventSeq > 0 ? eventSeq : null);
        logRow.setPreviousEventSeq(eventSeq > 0 ? previousSeq : null);
        logRow.setSagaId(context.sagaId());
        logRow.setOperator(context.operator());
        logRow.setTraceId(context.traceId());
        logRow.setOccurredAt(context.occurredAt());
        logRow.setProjectionStatus(DocOperationLog.PROJECTION_PENDING);
        logRow.setProjectionAttempts(0L);
        logRow.setNextProjectionTime(context.occurredAt());
        logRow.setProjectionLeaseOwner(EMPTY);
        logRow.setProjectionLeaseUntil(0L);
        logRow.setCreateTime(context.occurredAt());
        if (context.changesStatus()) {
            logRow.setHistoryJson(toJson(historySnapshot(fromStatus, toStatus, context)));
        }
        logRow.setAuditJson(toJson(auditSnapshot(context)));
        if (context.hasEvent()) {
            logRow.setEventJson(eventEnvelope(docCode, uuid, context, eventId,
                    eventSeq, previousSeq));
        }
        return logRow;
    }

    private Map<String, Object> historySnapshot(Long fromStatus, long toStatus,
                                                DocOperationContext context) {
        Map<String, Object> history = new LinkedHashMap<>();
        if (fromStatus != null) {
            history.put("from_status", fromStatus);
        }
        history.put("to_status", toStatus);
        history.put("operation", context.operation());
        history.put("operator", context.operator());
        history.put("operate_time", context.occurredAt());
        history.put("version_after", context.afterVersion());
        return history;
    }

    private Map<String, Object> auditSnapshot(DocOperationContext context) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("before", DocOperationContext.OP_CREATE.equals(context.operation())
                ? null : snapshot(context.beforeStatus(), context.beforeVersion(),
                context.beforeAttributes(), false));
        audit.put("after", context.deletedAfter() ? null
                : snapshot(context.afterStatus(), context.afterVersion(),
                context.afterAttributes(), false));
        return audit;
    }

    private Map<String, Object> snapshot(long status, long version,
                                         Map<String, Object> attributes, boolean deleted) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("current_status", status);
        snapshot.put("version", version);
        snapshot.put("attributes", attributes == null ? Map.of() : attributes);
        snapshot.put("deleted", deleted ? 1 : 0);
        return snapshot;
    }

    private String eventEnvelope(String docCode, String docUuid, DocOperationContext context,
                                 String eventId, long eventSeq, long previousSeq) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doc_uuid", docUuid);
        payload.put("doc_code", docCode);
        payload.put("doc_type", docCode);
        payload.put("operator", context.operator() == null ? "anonymous" : context.operator());
        payload.put("version", context.afterVersion());
        payload.put("event_seq", eventSeq);
        payload.put("previous_event_seq", previousSeq);
        payload.put("operation_id", context.operationId());
        payload.put("saga_id", context.sagaId() == null ? "" : context.sagaId());
        payload.put("event", context.event() == null ? "" : context.event());
        if (context.changesStatus()) {
            payload.put("from_status", context.beforeStatus());
            payload.put("to_status", context.afterStatus());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", context.eventType());
        envelope.put("schema_version", ENVELOPE_SCHEMA_VERSION);
        envelope.put("timestamp", context.occurredAt());
        envelope.put("payload", payload);
        if (context.traceId() != null && !context.traceId().isBlank()) {
            envelope.put("user_properties", Map.of("traceparent", context.traceId()));
        }
        return toJson(envelope);
    }

    // ==================== 行构造与映射 ====================

    private TsRow toRow(DocOperationLog logRow) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("record_kind", TsColumnValue.of(DocOperationLog.RECORD_KIND_OPERATION));
        columns.put("doc_code", TsColumnValue.of(logRow.getDocCode()));
        columns.put("doc_uuid", TsColumnValue.of(logRow.getDocUuid()));
        columns.put("current_status", TsColumnValue.of(logRow.getToStatus() == null
                ? 0L : logRow.getToStatus()));
        columns.put("deleted", TsColumnValue.of(DocDataRecord.DELETED));
        columns.put("operation_id", TsColumnValue.of(logRow.getOperationId()));
        columns.put("operation", TsColumnValue.of(logRow.getOperation()));
        if (logRow.getEvent() != null && !logRow.getEvent().isBlank()) {
            columns.put("event", TsColumnValue.of(logRow.getEvent()));
        }
        columns.put("schema_version", TsColumnValue.of(logRow.getSchemaVersion()));
        columns.put("request_digest", TsColumnValue.of(
                logRow.getRequestDigest() == null ? "" : logRow.getRequestDigest()));
        columns.put("expected_version", TsColumnValue.of(logRow.getExpectedVersion()));
        columns.put("version_after", TsColumnValue.of(logRow.getVersionAfter()));
        if (logRow.getFromStatus() != null) {
            columns.put("from_status", TsColumnValue.of(logRow.getFromStatus()));
        }
        if (logRow.getToStatus() != null) {
            columns.put("to_status", TsColumnValue.of(logRow.getToStatus()));
        }
        if (logRow.getEventId() != null) {
            columns.put("event_id", TsColumnValue.of(logRow.getEventId()));
        }
        if (logRow.getEventType() != null) {
            columns.put("event_type", TsColumnValue.of(logRow.getEventType()));
        }
        if (logRow.getEventSeq() != null) {
            columns.put("event_seq", TsColumnValue.of(logRow.getEventSeq()));
        }
        if (logRow.getPreviousEventSeq() != null) {
            columns.put("previous_event_seq", TsColumnValue.of(logRow.getPreviousEventSeq()));
        }
        if (logRow.getHistoryJson() != null) {
            columns.put("history_json", TsColumnValue.of(logRow.getHistoryJson()));
        }
        if (logRow.getAuditJson() != null) {
            columns.put("audit_json", TsColumnValue.of(logRow.getAuditJson()));
        }
        if (logRow.getEventJson() != null) {
            columns.put("event_json", TsColumnValue.of(logRow.getEventJson()));
        }
        if (logRow.getSagaId() != null) {
            columns.put("saga_id", TsColumnValue.of(logRow.getSagaId()));
        }
        columns.put("operator", TsColumnValue.of(
                logRow.getOperator() == null ? "anonymous" : logRow.getOperator()));
        if (logRow.getTraceId() != null) {
            columns.put("trace_id", TsColumnValue.of(logRow.getTraceId()));
        }
        columns.put("occurred_at", TsColumnValue.of(logRow.getOccurredAt()));
        columns.put("projection_status", TsColumnValue.of(logRow.getProjectionStatus()));
        columns.put("projection_attempts", TsColumnValue.of(logRow.getProjectionAttempts()));
        columns.put("next_projection_time", TsColumnValue.of(logRow.getNextProjectionTime()));
        columns.put("projection_lease_owner", TsColumnValue.of(
                logRow.getProjectionLeaseOwner() == null ? EMPTY : logRow.getProjectionLeaseOwner()));
        columns.put("projection_lease_until", TsColumnValue.of(logRow.getProjectionLeaseUntil()));
        columns.put("create_time", TsColumnValue.of(logRow.getCreateTime()));
        return new TsRow(TsPrimaryKey.builder()
                .add("partition_key", logRow.getPartitionKey())
                .add("uuid", logRow.getLogUuid())
                .build(), columns);
    }

    static TsPrimaryKey dataPk(String docCode, String uuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(docCode, uuid))
                .add("uuid", uuid)
                .build();
    }

    static String logUuid(String docUuid, String operationId) {
        return "op:" + docUuid + ":" + operationId;
    }

    private static String eventId(String operationId) {
        return "evt_" + operationId;
    }

    private DocDataRecord mapRow(TsRow row) {
        String uuid = String.valueOf(row.getPrimaryKey().get("uuid"));
        Long status = row.getLong("current_status");
        Long version = row.getLong("version");
        Long createTime = row.getLong("create_time");
        Long updateTime = row.getLong("update_time");
        Long deleted = row.getLong("deleted");
        return new DocDataRecord(uuid, row.getString("doc_code"),
                status == null ? -1L : status,
                version == null ? 0L : version,
                parseJsonMap(row.getString("attributes")),
                parseJsonList(row.getString("relations")),
                deleted != null && deleted == DocDataRecord.DELETED,
                row.getString("creator"), row.getString("create_dept"),
                createTime == null ? 0L : createTime,
                updateTime == null ? 0L : updateTime);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, LinkedHashMap.class);
            return map;
        } catch (JsonProcessingException e) {
            log.warn("[doc-data] attributes 列反序列化失败", e);
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            log.warn("[doc-data] relations 列反序列化失败", e);
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("单据数据 JSON 序列化失败", e);
        }
    }

    private static String digest(DocOperationContext context) {
        String canonical = context.operation() + "|" + context.beforeVersion() + "|"
                + context.afterVersion() + "|" + context.afterStatus() + "|"
                + context.eventType() + "|" + context.afterAttributes();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    /** 流转事务内业务中断（非条件冲突：单据缺失/已删），外层映射 40401。 */
    public static class DocTransitionAbortException extends com.example.runtime.domain.port.DocTransitionAbortException {

        private static final long serialVersionUID = 1L;

        public DocTransitionAbortException(String message) {
            super(message);
        }
    }
}
