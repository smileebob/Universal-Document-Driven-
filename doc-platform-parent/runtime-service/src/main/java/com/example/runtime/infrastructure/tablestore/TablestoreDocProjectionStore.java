package com.example.runtime.infrastructure.tablestore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocProjectionStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 操作日志 → history / outbox / audit 的幂等投影实现。
 *
 * <p>投影只使用日志行内的冻结快照，不读取主行最新状态；失败由上层
 * {@code DocOperationProjector} 退避重试并保留源日志。</p>
 */
@Component
public class TablestoreDocProjectionStore implements DocProjectionStore {

    static final String TABLE_HISTORY = "doc_data_history";
    static final String TABLE_OUTBOX = "event_outbox";
    static final String TABLE_AUDIT = "audit_log";

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public TablestoreDocProjectionStore(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    @Override
    public void putHistoryIfAbsent(DocOperationLog log) {
        TsRow row = new TsRow(historyPk(log), historyColumns(log));
        putImmutable(TABLE_HISTORY, row, () -> historyMatches(log),
                "历史行内容与既有不可变行冲突: doc=" + log.getDocUuid()
                        + ", seq=" + log.getVersionAfter());
    }

    @Override
    public void putOutboxIfAbsent(DocOperationLog log) {
        if (!log.hasEvent()) {
            return;
        }
        TsRow row = new TsRow(outboxPk(log), outboxColumns(log));
        putImmutable(TABLE_OUTBOX, row, () -> outboxMatches(log),
                "outbox 事件内容与既有稳定 eventId 行冲突: eventId=" + log.getEventId());
    }

    @Override
    public void putAuditIfAbsent(DocOperationLog log) {
        String logId = auditLogId(log);
        TsRow row = new TsRow(auditPk(logId), auditColumns(log));
        putImmutable(TABLE_AUDIT, row, () -> auditMatches(log, logId),
                "审计行内容与既有不可变行冲突: logId=" + logId);
    }

    /**
     * 条件写不可变行：EXPECT_NOT_EXIST；冲突时回读核对，一致视为幂等成功，
     * 不一致抛 {@link ProjectionConflictException}。
     */
    private void putImmutable(String table, TsRow row, java.util.function.BooleanSupplier matches,
                              String conflictMessage) {
        try {
            template.putRow(table, row, RowCondition.expectNotExist());
        } catch (TsConditionCheckException e) {
            if (!matches.getAsBoolean()) {
                throw new ProjectionConflictException(conflictMessage);
            }
        }
    }

    private boolean historyMatches(DocOperationLog log) {
        Optional<TsRow> existing = template.getRow(TABLE_HISTORY, historyPk(log));
        if (existing.isEmpty()) {
            return false;
        }
        Map<String, TsColumnValue> expected = historyColumns(log);
        TsRow row = existing.get();
        return sameColumn(row, expected, "doc_code")
                && sameNullableLong(row, expected, "from_status")
                && sameColumn(row, expected, "to_status")
                && sameColumn(row, expected, "operation")
                && sameColumn(row, expected, "operator")
                && sameColumn(row, expected, "operate_time")
                && sameColumn(row, expected, "version_after");
    }

    private boolean outboxMatches(DocOperationLog log) {
        Optional<TsRow> existing = template.getRow(TABLE_OUTBOX, outboxPk(log));
        if (existing.isEmpty()) {
            return false;
        }
        Map<String, TsColumnValue> expected = outboxColumns(log);
        TsRow row = existing.get();
        return sameColumn(row, expected, "event_type")
                && sameColumn(row, expected, "event_body")
                && sameColumn(row, expected, "target_code")
                && sameColumn(row, expected, "target_uuid")
                && sameColumn(row, expected, "event_seq");
    }

    private boolean auditMatches(DocOperationLog log, String logId) {
        Optional<TsRow> existing = template.getRow(TABLE_AUDIT, auditPk(logId));
        if (existing.isEmpty()) {
            return false;
        }
        Map<String, TsColumnValue> expected = auditColumns(log);
        TsRow row = existing.get();
        return sameColumn(row, expected, "target_code")
                && sameColumn(row, expected, "target_uuid")
                && sameColumn(row, expected, "operation")
                && sameColumn(row, expected, "operator")
                && sameNullableColumn(row, expected, "before_value")
                && sameNullableColumn(row, expected, "after_value")
                && sameColumn(row, expected, "operate_time");
    }

    private Map<String, TsColumnValue> historyColumns(DocOperationLog log) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of(log.getDocCode()));
        if (log.getFromStatus() != null) {
            columns.put("from_status", TsColumnValue.of(log.getFromStatus()));
        }
        columns.put("to_status", TsColumnValue.of(
                log.getToStatus() == null ? log.getVersionAfter() : log.getToStatus()));
        String historyOperation = log.getEvent() != null && !log.getEvent().isBlank()
                ? log.getEvent() : log.getOperation();
        columns.put("operation", TsColumnValue.of(
                historyOperation == null ? "transition" : historyOperation));
        columns.put("operator", TsColumnValue.of(nullToAnonymous(log.getOperator())));
        columns.put("operate_time", TsColumnValue.of(log.getOccurredAt()));
        columns.put("version_after", TsColumnValue.of(log.getVersionAfter()));
        columns.put("operation_id", TsColumnValue.of(log.getOperationId()));
        return columns;
    }

    private Map<String, TsColumnValue> outboxColumns(DocOperationLog log) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("event_type", TsColumnValue.of(log.getEventType()));
        columns.put("event_body", TsColumnValue.of(
                log.getEventJson() == null ? "{}" : log.getEventJson()));
        columns.put("target_code", TsColumnValue.of(log.getDocCode()));
        columns.put("target_uuid", TsColumnValue.of(log.getDocUuid()));
        columns.put("send_status", TsColumnValue.of(0L));
        columns.put("retry_count", TsColumnValue.of(0L));
        columns.put("next_retry_time", TsColumnValue.of(log.getOccurredAt()));
        columns.put("create_time", TsColumnValue.of(log.getOccurredAt()));
        if (log.getEventSeq() != null) {
            columns.put("event_seq", TsColumnValue.of(log.getEventSeq()));
        }
        if (log.getPreviousEventSeq() != null) {
            columns.put("previous_event_seq", TsColumnValue.of(log.getPreviousEventSeq()));
        }
        columns.put("operation_id", TsColumnValue.of(log.getOperationId()));
        return columns;
    }

    private Map<String, TsColumnValue> auditColumns(DocOperationLog log) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("target_type", TsColumnValue.of("doc"));
        columns.put("target_code", TsColumnValue.of(log.getDocCode()));
        columns.put("target_uuid", TsColumnValue.of(log.getDocUuid()));
        columns.put("operator", TsColumnValue.of(nullToAnonymous(log.getOperator())));
        columns.put("operation", TsColumnValue.of(
                log.getOperation() == null ? "unknown" : log.getOperation()));
        if (log.getTraceId() != null && !log.getTraceId().isBlank()) {
            columns.put("trace_id", TsColumnValue.of(log.getTraceId()));
        }
        JsonNode audit = parseAudit(log.getAuditJson());
        putJsonColumn(columns, "before_value", audit == null ? null : audit.get("before"));
        putJsonColumn(columns, "after_value", audit == null ? null : audit.get("after"));
        columns.put("operate_time", TsColumnValue.of(log.getOccurredAt()));
        columns.put("operation_id", TsColumnValue.of(log.getOperationId()));
        return columns;
    }

    private void putJsonColumn(Map<String, TsColumnValue> columns, String name, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        columns.put(name, TsColumnValue.of(node.toString()));
    }

    private JsonNode parseAudit(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean sameColumn(TsRow row, Map<String, TsColumnValue> expected, String name) {
        TsColumnValue value = expected.get(name);
        TsColumnValue actual = row.getColumn(name);
        return value == null ? actual == null : value.equals(actual);
    }

    private static boolean sameNullableColumn(TsRow row, Map<String, TsColumnValue> expected,
                                              String name) {
        TsColumnValue value = expected.get(name);
        TsColumnValue actual = row.getColumn(name);
        return value == null ? actual == null || actual.asString().isEmpty()
                : value.equals(actual);
    }

    private static boolean sameNullableLong(TsRow row, Map<String, TsColumnValue> expected,
                                            String name) {
        TsColumnValue value = expected.get(name);
        TsColumnValue actual = row.getColumn(name);
        if (value == null) {
            return actual == null;
        }
        return actual != null && actual.asLong() == value.asLong();
    }

    private static String nullToAnonymous(String operator) {
        return operator == null || operator.isBlank() ? "anonymous" : operator;
    }

    private static TsPrimaryKey historyPk(DocOperationLog log) {
        return TsPrimaryKey.builder()
                .add("partition_key", log.getPartitionKey())
                .add("uuid", log.getDocUuid())
                .add("seq", log.getVersionAfter())
                .build();
    }

    private static TsPrimaryKey outboxPk(DocOperationLog log) {
        return TsPrimaryKey.builder()
                .add("partition_key", log.getPartitionKey())
                .add("event_id", log.getEventId())
                .build();
    }

    private static TsPrimaryKey auditPk(String logId) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.logKey(logId))
                .add("log_id", logId)
                .build();
    }

    static String auditLogId(DocOperationLog log) {
        return "op:" + log.getOperationId();
    }
}
