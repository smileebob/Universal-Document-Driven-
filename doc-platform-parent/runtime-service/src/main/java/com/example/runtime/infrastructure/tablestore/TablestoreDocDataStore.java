package com.example.runtime.infrastructure.tablestore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.port.DocDataStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据数据存储 Tablestore 实现（design/03 §2.2 / §2.10，design/05 §2.2 T1-T6）。
 *
 * <p><b>OTS 局部事务表级作用域整改</b>：真实 OTS 局部事务是表级作用域（
 * StartLocalTransaction(anchorTable=doc_data) 返回的事务 ID 仅可用于 doc_data
 * 自身读写，跨表写报 OTSSessionNotExist），原「同分区键即可同事务」假设不成立。
 * 现改为：局部事务内只写 anchor 表 doc_data（主行 CAS）；history 流水 /
 * outbox 事件 / 流转审计均移至事务提交成功后以幂等写补写（确定性主键
 * putRow 覆盖），失败仅 WARN（[doc-data] 前缀）不阻断主流程：outbox 依赖
 * 5s 扫描兜底 + 八级退避，history/audit 为准最终一致（与平台
 * Outbox/Saga 补偿哲学一致）。Saga/规则/Redis 一律事务外（红线）。</p>
 *
 * <p>审计行以 hash4 变体分区键写入 audit_log（WriteGuard 场景 3 豁免，
 * design/03 §6.1）；unique_index 占位为事务外独立 EXPECT_NOT_EXIST 条件写，
 * 本就不在局部事务内，不受表级作用域约束影响。</p>
 *
 * <p>阶段三 T5 升级：outbox 行 event_type 采用 prd/12 §1.10 字典
 * （DOC_STATUS_CHANGED / DOC_CREATED），event_body 为 design/07 v1.1 信封
 * （event_id/event_type/schema_version/timestamp/payload/user_properties），
 * next_retry_time=创建时刻（中继即时可投）；event_id 提前预生成（确定性主键，
 * 补写幂等），经 TransitionOutcome 返回供事务提交后即时首发入队。</p>
 */
@Component
public class TablestoreDocDataStore implements DocDataStore {

    static final String TABLE_DOC_DATA = "doc_data";
    static final String TABLE_DOC_HISTORY = "doc_data_history";
    static final String TABLE_EVENT_OUTBOX = "event_outbox";
    static final String TABLE_AUDIT_LOG = "audit_log";

    /** 事件信封 schema 版本（design/07 v1.1） */
    private static final String ENVELOPE_SCHEMA_VERSION = "1.1";

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
                .filter(row -> {
                    Long deleted = row.getLong("deleted");
                    return deleted == null || deleted == DocDataRecord.ACTIVE;
                })
                .map(this::mapRow);
    }

    @Override
    public List<Map<String, Object>> findHistory(String docCode, String uuid) {
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        // 分区级 GetRange（非全表扫描）+ uuid 过滤（同分区可能共享 hash4）+ seq 升序
        return template.getPartitionRange(TABLE_DOC_HISTORY, partitionKey).stream()
                .filter(row -> uuid.equals(String.valueOf(row.getPrimaryKey().get("uuid"))))
                .sorted(Comparator.comparingLong(TablestoreDocDataStore::seqOf))
                .map(this::mapHistoryRow)
                .toList();
    }

    /** 历史行主键 seq 提取（缺失按 0 保守处理） */
    private static long seqOf(TsRow row) {
        return row.getPrimaryKey().get("seq") instanceof Number number ? number.longValue() : 0L;
    }

    /** 历史行 → snake_case 视图（字段名与 doc_data_history 表定义对齐） */
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

    // ==================== 创建（主行单表事务 + 提交后幂等补写 history/outbox） ====================

    @Override
    public void create(DocDataRecord record, String operator, String traceId) {
        String partitionKey = PartitionKeys.dataKey(record.getDocCode(), record.getUuid());
        // OTS 硬约束：局部事务为表级作用域，事务内只写 anchor 表 doc_data 主行；
        // history/outbox 为跨表写，移至提交后幂等补写（确定性主键，重试不重复）
        template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
            Map<String, TsColumnValue> columns = new LinkedHashMap<>();
            columns.put("doc_code", TsColumnValue.of(record.getDocCode()));
            columns.put("current_status", TsColumnValue.of(record.getCurrentStatus()));
            columns.put("version", TsColumnValue.of(record.getVersion()));
            columns.put("attributes", TsColumnValue.of(toJson(record.getAttributes())));
            columns.put("relations", TsColumnValue.of(toJson(record.getRelations())));
            columns.put("deleted", TsColumnValue.of(DocDataRecord.ACTIVE));
            if (record.getCreator() != null) {
                columns.put("creator", TsColumnValue.of(record.getCreator()));
            }
            if (record.getCreateDept() != null) {
                columns.put("create_dept", TsColumnValue.of(record.getCreateDept()));
            }
            columns.put("create_time", TsColumnValue.of(record.getCreateTime()));
            columns.put("update_time", TsColumnValue.of(record.getUpdateTime()));
            // EXPECT_NOT_EXIST 幂等建行（uuid 服务端生成，撞键即 40901 语义）
            ops.putRow(TABLE_DOC_DATA, new TsRow(dataPk(record.getDocCode(), record.getUuid()), columns),
                    RowCondition.expectNotExist());
            return null;
        });

        // ---- 提交后幂等补写（best-effort，失败 WARN 不阻断主流程） ----
        // 创建历史行 seq=1=version（RD-08：status_history 伴生，只增不改；确定性主键幂等，
        // expectNotExist 拒绝静默覆盖）
        putHistoryRowBestEffort(historyRow(partitionKey, record.getDocCode(),
                record.getUuid(), record.getVersion(), null, record.getCurrentStatus(),
                "create", operator, record.getUpdateTime(), record.getVersion()),
                record.getDocCode() + "/" + record.getUuid() + " 创建历史行");

        // DOC_CREATED outbox 行（EV-01：建单事件，prd/12 §1.10）；
        // event_id 预生成 → 主键确定性 → putRow 覆盖幂等；失败时该事件无行可扫，
        // 依赖 WARN 日志告警人工处置（与提交后补写失败的补偿口径一致）
        long now = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doc_uuid", record.getUuid());
        payload.put("doc_code", record.getDocCode());
        payload.put("doc_type", record.getDocCode());
        payload.put("operator", operator == null ? "anonymous" : operator);
        payload.put("version", record.getVersion());
        payload.put("create_time", record.getCreateTime());
        putRowBestEffort(TABLE_EVENT_OUTBOX,
                outboxRow(partitionKey,
                        DocMqConstants.EventTypes.DOC_CREATED, record.getDocCode(),
                        record.getUuid(), payload, now, traceId),
                record.getDocCode() + "/" + record.getUuid() + " DOC_CREATED outbox 行");
    }

    // ==================== 可编辑态修改 / 逻辑删 ====================

    @Override
    public boolean updateEditable(String docCode, String uuid, long expectVersion,
                                  Map<String, Object> attributes, List<Map<String, Object>> relations,
                                  long updateTime) {
        // version CAS（TX-01）：服务层快照与落库版本必须一致；状态流转/逻辑删除同样
        // 递增 version，因此该条件同时覆盖“状态已漂移”和“并发属性修改”。
        List<ColumnUpdate> updates = List.of(
                ColumnUpdate.put("attributes", TsColumnValue.of(toJson(attributes))),
                ColumnUpdate.put("relations", TsColumnValue.of(toJson(relations))),
                ColumnUpdate.put("update_time", TsColumnValue.of(updateTime)));
        try {
            template.updateWithVersion(TABLE_DOC_DATA, dataPk(docCode, uuid), expectVersion, updates);
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    @Override
    public boolean logicalDelete(String docCode, String uuid, long expectVersion, long updateTime) {
        // updateWithVersion 自身写入 version+1，此处不得重复包含 version 列
        List<ColumnUpdate> updates = List.of(
                ColumnUpdate.put("deleted", TsColumnValue.of(DocDataRecord.DELETED)),
                ColumnUpdate.put("update_time", TsColumnValue.of(updateTime)));
        try {
            template.updateWithVersion(TABLE_DOC_DATA, dataPk(docCode, uuid), expectVersion, updates);
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    // ==================== 流转收口（单表事务 T1/T3 + 提交后幂等补写 T4/T5/审计） ====================

    @Override
    public TransitionOutcome commitTransition(String docCode, String uuid, long expectedVersion,
                                              long fromStatus, long toStatus, String operator,
                                              String event, String sagaId) {
        String partitionKey = PartitionKeys.dataKey(docCode, uuid);
        TsPrimaryKey mainPk = dataPk(docCode, uuid);
        long now = System.currentTimeMillis();
        // outbox event_id 预生成（确定性主键 → 补写幂等，随 TransitionOutcome 返回供提交后即时首发）
        String eventId = UUID.randomUUID().toString();

        // OTS 硬约束：局部事务仅允许写 anchorTable(doc_data) 自身；
        // history/outbox/审计跨表写移至提交后幂等补写。
        // seq 竞态整改（评审项 1）：原「事务外 GetRange 算 maxSeq+1」在锁失效
        // 窗口下顺序流转交错会算出相同 seq，无条件 putRow 静默覆盖先写流水；
        // 现 history seq 直接采用 CAS 保护、天然单调唯一的 newVersion（create 路径
        // version=1/seq=1 天然对齐；正常单线流转下 seq 序列 1,2,3… 与旧 maxSeq+1 等价）。
        long newVersion = template.executeInLocalTransaction(TABLE_DOC_DATA, partitionKey, ops -> {
            // T1：事务内 GetRow 主行（锁内二次确认，防锁外快照漂移）
            TsRow main = ops.getRow(TABLE_DOC_DATA, mainPk).orElseThrow(() ->
                    new DocTransitionAbortException("单据不存在或已删除: " + uuid));
            Long deleted = main.getLong("deleted");
            if (deleted != null && deleted == DocDataRecord.DELETED) {
                throw new DocTransitionAbortException("单据已删除: " + uuid);
            }
            long snapshotStatus = main.getLong("current_status") == null
                    ? -1L : main.getLong("current_status");
            if (snapshotStatus != fromStatus) {
                // 状态漂移（并发流转先行提交）→ 中断事务，外层映射 40901
                throw new TsConditionCheckException("current_status 已变化: expect="
                        + fromStatus + ", actual=" + snapshotStatus);
            }
            long snapshotVersion = main.getLong("version") == null
                    ? 0L : main.getLong("version");
            if (snapshotVersion != expectedVersion) {
                // 快照版本漂移（规则/Saga 依据的旧数据被并发修改）→ 40901，TX-02
                throw new TsConditionCheckException("version 已变化: expect="
                        + expectedVersion + ", actual=" + snapshotVersion);
            }

            // T3：主行双列 CAS（current_status 条件列 + version 列 put 新值）
            long versionAfter = snapshotVersion + 1;
            List<ColumnUpdate> updates = List.of(
                    ColumnUpdate.put("current_status", TsColumnValue.of(toStatus)),
                    ColumnUpdate.put("version", TsColumnValue.of(versionAfter)),
                    ColumnUpdate.put("update_time", TsColumnValue.of(now)));
            ops.updateRow(TABLE_DOC_DATA, mainPk, updates,
                    RowCondition.expectExistAndColumnEq("current_status",
                            TsColumnValue.of(fromStatus)));

            // T6：函数正常返回 → 模板提交事务（主行落库即用户可见结果可靠）
            return versionAfter;
        });

        // ---- 提交后幂等补写（best-effort，失败 WARN 不阻断，准最终一致） ----
        // T4：历史行（只增不改；确定性主键 {partition_key,uuid,seq=newVersion}，
        // expectNotExist 条件写：撞键宁可缺行走 WARN 告警，不可静默覆盖先写流水）
        putHistoryRowBestEffort(historyRow(partitionKey, docCode, uuid, newVersion,
                fromStatus, toStatus, event, operator, now, newVersion),
                docCode + "/" + uuid + " 流转历史行 seq=" + newVersion);

        // T5：event_outbox 行（DOC_STATUS_CHANGED，design/07 v1.1 信封，阶段三 relay 消费）；
        // 补写失败时该事件无行可扫，WARN 告警人工处置
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doc_uuid", uuid);
        payload.put("doc_code", docCode);
        payload.put("doc_type", docCode);
        payload.put("from_status", fromStatus);
        payload.put("to_status", toStatus);
        payload.put("operator", operator == null ? "" : operator);
        payload.put("saga_id", sagaId == null ? "" : sagaId);
        payload.put("version", newVersion);
        payload.put("event", event == null ? "" : event);
        putRowBestEffort(TABLE_EVENT_OUTBOX, new TsRow(
                        outboxPk(partitionKey, eventId),
                        outboxColumns(DocMqConstants.EventTypes.DOC_STATUS_CHANGED,
                                docCode, uuid, payload, now, MDC.get("traceId"), eventId)),
                docCode + "/" + uuid + " DOC_STATUS_CHANGED outbox 行 eventId=" + eventId);

        // ⑧ 流转审计补写（audit_log hash4 变体分区键，WriteGuard 豁免）；
        // log_id 随机不可重放，失败仅单次 WARN，不重试避免重复审计行
        Map<String, TsColumnValue> audit = new LinkedHashMap<>();
        audit.put("target_type", TsColumnValue.of("doc"));
        audit.put("target_code", TsColumnValue.of(docCode));
        audit.put("target_uuid", TsColumnValue.of(uuid));
        audit.put("operator", TsColumnValue.of(operator == null ? "anonymous" : operator));
        audit.put("operation", TsColumnValue.of("transition"));
        // trace 闭环：流转审计落 trace_id 列（MDC 由网关/直连过滤器注入，design/11 §四）
        String auditTraceId = MDC.get("traceId");
        if (auditTraceId != null && !auditTraceId.isBlank()) {
            audit.put("trace_id", TsColumnValue.of(auditTraceId));
        }
        // RD-12 before/after 快照列（对齐 audit_log_index，JSON Diff 可用，design/03 §2.10）
        audit.put("before_value", TsColumnValue.of(toJson(Map.of("current_status", fromStatus))));
        audit.put("after_value", TsColumnValue.of(toJson(Map.of(
                "current_status", toStatus, "event", event == null ? "" : event))));
        audit.put("operate_time", TsColumnValue.of(now));
        putRowBestEffort(TABLE_AUDIT_LOG, new TsRow(
                        TsPrimaryKey.builder()
                                .add("partition_key", partitionKey)
                                .add("log_id", UUID.randomUUID().toString())
                                .build(), audit),
                docCode + "/" + uuid + " 流转审计行");

        return new TransitionOutcome(newVersion, newVersion, eventId);
    }

    /**
     * 提交后幂等补写（best-effort）：确定性主键 putRow 覆盖，失败仅 WARN
     * 不阻断主流程（主数据已在单表事务内可靠落库；history/audit 为准最终
     * 一致，outbox 依赖 5s 扫描兜底 + 八级退避）。
     */
    private void putRowBestEffort(String table, TsRow row, String describe) {
        try {
            template.putRow(table, row);
        } catch (RuntimeException e) {
            log.warn("[doc-data] 提交后补写失败（不阻断主流程，依赖既有补偿机制）: table={}, {}",
                    table, describe, e);
        }
    }

    /**
     * 历史行补写（seq 竞态整改）：EXPECT_NOT_EXIST 条件写，撞键宁可缺行走
     * WARN 告警，不可静默覆盖先写流水（history 只增不改，缺行可由告警人工补录）。
     */
    private void putHistoryRowBestEffort(TsRow row, String describe) {
        try {
            template.putRow(TABLE_DOC_HISTORY, row, RowCondition.expectNotExist());
        } catch (TsConditionCheckException e) {
            log.warn("[doc-data] 历史行已存在，跳过补写（seq 冲突拒绝覆盖，需人工核对）: {}",
                    describe);
        } catch (RuntimeException e) {
            log.warn("[doc-data] 提交后补写失败（不阻断主流程，依赖既有补偿机制）: table={}, {}",
                    TABLE_DOC_HISTORY, describe, e);
        }
    }

    // ==================== outbox 行构造（design/07 v1.1 信封） ====================

    /** outbox 行（独立事件 ID 生成，建单路径用） */
    private TsRow outboxRow(String partitionKey, String eventType, String docCode,
                            String uuid, Map<String, Object> payload, long now, String traceId) {
        String eventId = UUID.randomUUID().toString();
        return new TsRow(outboxPk(partitionKey, eventId),
                outboxColumns(eventType, docCode, uuid, payload, now, traceId, eventId));
    }

    /**
     * outbox 列集：event_type/event_body(v1.1 信封)/target_code/target_uuid/
     * send_status=0/retry_count=0/next_retry_time=now/create_time。
     */
    private Map<String, TsColumnValue> outboxColumns(String eventType, String docCode,
                                                     String uuid, Map<String, Object> payload,
                                                     long now, String traceId, String eventId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", eventType);
        envelope.put("schema_version", ENVELOPE_SCHEMA_VERSION);
        envelope.put("timestamp", now);
        envelope.put("payload", payload);
        if (traceId != null && !traceId.isBlank()) {
            envelope.put("user_properties", Map.of("traceparent", traceId));
        }
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("event_type", TsColumnValue.of(eventType));
        columns.put("event_body", TsColumnValue.of(toJson(envelope)));
        columns.put("target_code", TsColumnValue.of(docCode));
        columns.put("target_uuid", TsColumnValue.of(uuid));
        columns.put("send_status", TsColumnValue.of(0L));
        columns.put("retry_count", TsColumnValue.of(0L));
        columns.put("next_retry_time", TsColumnValue.of(now));
        columns.put("create_time", TsColumnValue.of(now));
        return columns;
    }

    // ==================== 行构造与映射 ====================

    /** 主键构造：partition_key + uuid */
    static TsPrimaryKey dataPk(String docCode, String uuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(docCode, uuid))
                .add("uuid", uuid)
                .build();
    }

    /** outbox 主键构造：partition_key + event_id */
    static TsPrimaryKey outboxPk(String partitionKey, String eventId) {
        return TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("event_id", eventId)
                .build();
    }

    private TsRow historyRow(String partitionKey, String docCode, String uuid, long seq,
                             Long fromStatus, long toStatus, String operation,
                             String operator, long operateTime, long versionAfter) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of(docCode));
        if (fromStatus != null) {
            columns.put("from_status", TsColumnValue.of(fromStatus));
        }
        columns.put("to_status", TsColumnValue.of(toStatus));
        columns.put("operation", TsColumnValue.of(operation == null ? "transition" : operation));
        columns.put("operator", TsColumnValue.of(operator == null ? "anonymous" : operator));
        columns.put("operate_time", TsColumnValue.of(operateTime));
        columns.put("version_after", TsColumnValue.of(versionAfter));
        return new TsRow(TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("uuid", uuid)
                .add("seq", seq)
                .build(), columns);
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

    /**
     * 流转事务内业务中断（非条件冲突：单据缺失/已删），外层映射 40401。
     */
    public static class DocTransitionAbortException extends com.example.runtime.domain.port.DocTransitionAbortException {

        private static final long serialVersionUID = 1L;

        public DocTransitionAbortException(String message) {
            super(message);
        }
    }
}

