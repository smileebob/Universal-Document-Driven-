package com.example.runtime.infrastructure.tablestore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.constants.DocMqConstants;
import com.example.common.infrastructure.tablestore.LocalTransactionOps;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TablestoreAccessException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.event.DocOperationLog;
import com.example.runtime.domain.event.DocOperationLogStore;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocOperationContext;
import com.example.runtime.domain.port.DocDataStore.TransitionOutcome;
import com.example.runtime.service.event.DocOperationProjector;
import com.example.runtime.service.event.OutboxRelayService;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 操作日志同表事务 + 可重放投影验证。
 *
 * <p>核心不变量：主行业务变更与不可变日志行在同一 OTS 局部事务（仅 doc_data）；
 * history/outbox/audit 在日志投影确认后才出现；投影失败保留日志并可重试。</p>
 */
class TablestoreDocDataStoreTxScopeTest {

    private static final String DOC = "po";

    private TxScopeRecordingTablestore template;
    private TablestoreDocDataStore store;
    private DocOperationProjector projector;
    private DocOperationLogStore logStore;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        template = new TxScopeRecordingTablestore();
        new TablestoreBootstrap(template).run();
        store = new TablestoreDocDataStore(template, mapper);
        logStore = new TablestoreDocOperationLogStore(template);
        projector = new DocOperationProjector(logStore,
                new TablestoreDocProjectionStore(template, mapper),
                mock(OutboxRelayService.class));
    }

    private DocDataRecord newRecord(String uuid) {
        long now = System.currentTimeMillis();
        return new DocDataRecord(uuid, DOC, 100L, 1L,
                Map.of("amount", 100), List.of(), false, "alice", "dept", now, now);
    }

    private DocOperationContext createContext(String uuid, String operationId) {
        return new DocOperationContext(operationId, DocOperationContext.OP_CREATE,
                "alice", "trace-1", null, "create",
                DocMqConstants.EventTypes.DOC_CREATED, 0L,
                0L, 0L, Map.of(), 1L, 100L,
                Map.of("amount", 100), List.of(), false, System.currentTimeMillis());
    }

    private DocOperationContext updateContext(long version, long status,
                                              Map<String, Object> before,
                                              Map<String, Object> after, String operationId) {
        return new DocOperationContext(operationId, DocOperationContext.OP_UPDATE,
                "alice", null, null, null, null, 0L,
                version, status, before, version + 1, status, after, List.of(),
                false, System.currentTimeMillis());
    }

    private DocOperationContext transitionContext(long version, long from, long to,
                                                  String operationId, String event) {
        return new DocOperationContext(operationId, DocOperationContext.OP_TRANSITION,
                "bob", null, null, event,
                DocMqConstants.EventTypes.DOC_STATUS_CHANGED, 0L,
                version, from, Map.of("amount", 100), version + 1, to,
                Map.of("amount", 100), List.of(), false, System.currentTimeMillis());
    }

    private String createAndProject() {
        String uuid = UUID.randomUUID().toString();
        String operationId = "op-create-" + uuid;
        store.create(newRecord(uuid), createContext(uuid, operationId));
        assertThat(projector.projectNow(DOC, uuid, operationId)).isTrue();
        return uuid;
    }

    // ==================== 创建链路 ====================

    @Test
    @DisplayName("创建：主行+日志同表事务；投影前无 history/outbox/audit")
    void create_txScopeOnlyDocData_logAtomic_projectionLater() {
        String uuid = UUID.randomUUID().toString();
        String operationId = "op-create-" + uuid;
        store.create(newRecord(uuid), createContext(uuid, operationId));

        // 事务内只触达 anchor 表 doc_data（日志行同表同分区）
        assertThat(template.touchedTablesInTx()).containsExactly("doc_data");
        assertThat(store.find(DOC, uuid)).isPresent();
        // 主行 + 日志行两行
        assertThat(template.getPartitionRange("doc_data", PartitionKeys.dataKey(DOC, uuid)))
                .hasSize(2);
        assertThat(store.findHistory(DOC, uuid)).isEmpty();
        assertThat(template.getPartitionRange("event_outbox",
                PartitionKeys.dataKey(DOC, uuid))).isEmpty();
        assertThat(template.getRange("audit_log")).isEmpty();

        // 投影后三类派生行出现
        assertThat(projector.projectNow(DOC, uuid, operationId)).isTrue();
        assertThat(store.findHistory(DOC, uuid)).hasSize(1);
        assertThat(store.findHistory(DOC, uuid).get(0).get("operation")).isEqualTo("create");
        assertThat(template.getPartitionRange("event_outbox",
                PartitionKeys.dataKey(DOC, uuid))).hasSize(1);
        assertThat(template.getRange("audit_log")).hasSize(1);
    }

    // ==================== 流转链路 ====================

    @Test
    @DisplayName("流转：事务内仅 doc_data；投影后 history/outbox/审计补齐")
    void commitTransition_txScopeOnlyDocData_projection() {
        String uuid = createAndProject();
        template.resetTxScope();

        String operationId = "op-transition-" + uuid;
        TransitionOutcome outcome = store.commitTransition(DOC, uuid, 1L, 100L, 10L,
                transitionContext(1L, 100L, 10L, operationId, "submit"));

        assertThat(template.touchedTablesInTx()).containsExactly("doc_data");
        assertThat(outcome.version()).isEqualTo(2L);
        assertThat(outcome.historySeq()).isEqualTo(2L);
        assertThat(outcome.eventSeq()).isEqualTo(2L);
        assertThat(store.findHistory(DOC, uuid)).hasSize(1);

        assertThat(projector.projectNow(DOC, uuid, operationId)).isTrue();
        assertThat(store.findHistory(DOC, uuid)).hasSize(2);
        assertThat(template.getPartitionRange("event_outbox",
                PartitionKeys.dataKey(DOC, uuid))).hasSize(2);
        assertThat(template.getRange("audit_log")).hasSize(2);
    }

    // ==================== 版本 CAS（TX-01/TX-02） ====================

    @Test
    @DisplayName("可编辑修改：同一 version 仅首次提交成功，陈旧快照被拒绝")
    void updateEditable_staleVersionRejected() {
        String uuid = createAndProject();

        assertThat(store.updateEditable(DOC, uuid, 1L, 100L,
                updateContext(1L, 100L, Map.of("amount", 100),
                        Map.of("amount", 150), "op-update-1"))).isTrue();
        assertThat(store.updateEditable(DOC, uuid, 1L, 100L,
                updateContext(1L, 100L, Map.of("amount", 150),
                        Map.of("amount", 999), "op-update-2"))).isFalse();
        assertThat(store.find(DOC, uuid).orElseThrow().getVersion()).isEqualTo(2L);
        assertThat(store.find(DOC, uuid).orElseThrow().getAttributes()).containsEntry("amount", 150);
        // 普通属性更新不产生领域事件，不得占用 event_seq
        DocOperationLog updateLog = logStore.find(PartitionKeys.dataKey(DOC, uuid),
                "op:" + uuid + ":op-update-1").orElseThrow();
        assertThat(updateLog.getEventSeq()).isNull();
    }

    @Test
    @DisplayName("流转提交：规则/Saga 使用的旧版本在提交时被拒绝（即使状态未变）")
    void commitTransition_staleSnapshotRejected() {
        String uuid = createAndProject();
        assertThat(store.updateEditable(DOC, uuid, 1L, 100L,
                updateContext(1L, 100L, Map.of("amount", 100),
                        Map.of("amount", 150), "op-update-3"))).isTrue();

        assertThatThrownBy(() -> store.commitTransition(DOC, uuid, 1L, 100L, 10L,
                transitionContext(1L, 100L, 10L, "op-transition-stale", "submit")))
                .isInstanceOf(TsConditionCheckException.class);
        assertThat(store.find(DOC, uuid).orElseThrow().getCurrentStatus()).isEqualTo(100L);
    }

    // ==================== 主行+日志原子性 ====================

    @Test
    @DisplayName("日志行写入失败：主行与日志一起回滚（不产生半提交）")
    void logWriteFailure_rollsBackMainRow() {
        LogWriteFailingTablestore failing = new LogWriteFailingTablestore();
        new TablestoreBootstrap(failing).run();
        TablestoreDocDataStore failingStore = new TablestoreDocDataStore(failing, mapper);
        String uuid = UUID.randomUUID().toString();

        assertThatThrownBy(() -> failingStore.create(newRecord(uuid),
                createContext(uuid, "op-create-" + uuid)))
                .isInstanceOf(TablestoreAccessException.class);
        assertThat(failingStore.find(DOC, uuid)).isEmpty();
        assertThat(failing.getPartitionRange("doc_data", PartitionKeys.dataKey(DOC, uuid)))
                .isEmpty();
        assertThat(failing.getRange("doc_data_history")).isEmpty();
        assertThat(failing.getRange("event_outbox")).isEmpty();
    }

    // ==================== 投影失败保留日志 ====================

    @Test
    @DisplayName("history 投影失败不影响主行，日志保留待重试")
    void projectionFailure_keepsLogAndMainRow() {
        HistoryWriteFailingTablestore failing = new HistoryWriteFailingTablestore();
        new TablestoreBootstrap(failing).run();
        TablestoreDocDataStore failingStore =
                new TablestoreDocDataStore(failing, mapper);
        DocOperationLogStore failingLogStore =
                new TablestoreDocOperationLogStore(failing);
        DocOperationProjector failingProjector = new DocOperationProjector(failingLogStore,
                new TablestoreDocProjectionStore(failing, mapper),
                mock(OutboxRelayService.class));
        String uuid = UUID.randomUUID().toString();
        String operationId = "op-create-" + uuid;

        failingStore.create(newRecord(uuid), createContext(uuid, operationId));
        assertThat(failingStore.find(DOC, uuid)).isPresent();
        assertThat(failingStore.findHistory(DOC, uuid)).isEmpty();

        assertThat(failingProjector.projectNow(DOC, uuid, operationId)).isFalse();
        DocOperationLog operationLog = failingLogStore.find(
                PartitionKeys.dataKey(DOC, uuid), "op:" + uuid + ":" + operationId)
                .orElseThrow();
        assertThat(operationLog.getProjectionStatus())
                .isEqualTo(DocOperationLog.PROJECTION_PENDING);
        assertThat(operationLog.getProjectionAttempts()).isEqualTo(1L);
        assertThat(failing.touchedTablesInTx()).containsExactly("doc_data");
    }

    // ==================== seq 竞态整改 ====================

    @Test
    @DisplayName("history seq=version：多次流转与投影后序列 1,2,3")
    void historySeq_equalsVersion_acrossTransitions() {
        String uuid = createAndProject();
        String op1 = "op-t1-" + uuid;
        TransitionOutcome first = store.commitTransition(DOC, uuid, 1L, 100L, 10L,
                transitionContext(1L, 100L, 10L, op1, "submit"));
        projector.projectNow(DOC, uuid, op1);
        String op2 = "op-t2-" + uuid;
        TransitionOutcome second = store.commitTransition(DOC, uuid, 2L, 10L, 20L,
                transitionContext(2L, 10L, 20L, op2, "approve"));
        projector.projectNow(DOC, uuid, op2);

        assertThat(first.version()).isEqualTo(2L);
        assertThat(first.historySeq()).isEqualTo(first.version());
        assertThat(second.version()).isEqualTo(3L);
        assertThat(second.historySeq()).isEqualTo(second.version());

        List<Map<String, Object>> history = store.findHistory(DOC, uuid);
        assertThat(history).hasSize(3);
        assertThat(history).extracting(row -> row.get("seq"))
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("投影撞键：不覆盖既有历史/审计，日志转人工")
    void historyWrite_expectNotExist_neverOverwritesExistingRow() {
        String uuid = createAndProject();
        template.putRow("doc_data_history", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(DOC, uuid))
                .add("uuid", uuid)
                .add("seq", 2L)
                .build(), Map.of(
                "doc_code", TsColumnValue.of(DOC),
                "to_status", TsColumnValue.of(999L),
                "operation", TsColumnValue.of("sentinel"),
                "operator", TsColumnValue.of("first-writer"),
                "operate_time", TsColumnValue.of(1L),
                "version_after", TsColumnValue.of(2L))));

        String operationId = "op-conflict-" + uuid;
        TransitionOutcome outcome = store.commitTransition(DOC, uuid, 1L, 100L, 10L,
                transitionContext(1L, 100L, 10L, operationId, "submit"));
        assertThat(outcome.version()).isEqualTo(2L);
        assertThat(projector.projectNow(DOC, uuid, operationId)).isFalse();

        List<Map<String, Object>> history = store.findHistory(DOC, uuid);
        assertThat(history).hasSize(2);
        assertThat(history).extracting(row -> String.valueOf(row.get("operation")))
                .containsExactly("create", "sentinel");
        assertThat(history.get(1).get("operator")).isEqualTo("first-writer");

        DocOperationLog operationLog = logStore.find(PartitionKeys.dataKey(DOC, uuid),
                "op:" + uuid + ":" + operationId).orElseThrow();
        assertThat(operationLog.getProjectionStatus()).isEqualTo(DocOperationLog.PROJECTION_MANUAL);
    }

    // ==================== 测试桩 ====================

    static class TxScopeRecordingTablestore extends InMemoryTablestore {

        private final Set<String> txTouchedTables = new LinkedHashSet<>();
        private final java.util.function.Predicate<TsRow> txWriteFailure;

        TxScopeRecordingTablestore() {
            this(row -> false);
        }

        TxScopeRecordingTablestore(java.util.function.Predicate<TsRow> txWriteFailure) {
            this.txWriteFailure = txWriteFailure;
        }

        Set<String> touchedTablesInTx() {
            return txTouchedTables;
        }

        void resetTxScope() {
            txTouchedTables.clear();
        }

        @Override
        public <T> T executeInLocalTransaction(String anchorTable, String partitionKey,
                                               Function<LocalTransactionOps, T> action) {
            return super.executeInLocalTransaction(anchorTable, partitionKey,
                    ops -> action.apply(new RecordingOps(ops)));
        }

        private final class RecordingOps implements LocalTransactionOps {

            private final LocalTransactionOps delegate;

            private RecordingOps(LocalTransactionOps delegate) {
                this.delegate = delegate;
            }

            @Override
            public Optional<TsRow> getRow(String table, TsPrimaryKey pk) {
                txTouchedTables.add(table);
                return delegate.getRow(table, pk);
            }

            @Override
            public void putRow(String table, TsRow row) {
                txTouchedTables.add(table);
                if (txWriteFailure.test(row)) {
                    throw new TablestoreAccessException("注入故障: 事务内日志写入失败");
                }
                delegate.putRow(table, row);
            }

            @Override
            public void putRow(String table, TsRow row, RowCondition condition) {
                txTouchedTables.add(table);
                if (txWriteFailure.test(row)) {
                    throw new TablestoreAccessException("注入故障: 事务内日志写入失败");
                }
                delegate.putRow(table, row, condition);
            }

            @Override
            public void updateRow(String table, TsPrimaryKey pk, List<ColumnUpdate> updates,
                                  RowCondition condition) {
                txTouchedTables.add(table);
                delegate.updateRow(table, pk, updates, condition);
            }

            @Override
            public void deleteRow(String table, TsPrimaryKey pk) {
                txTouchedTables.add(table);
                delegate.deleteRow(table, pk);
            }

            @Override
            public List<TsRow> getPartitionRows(String table) {
                txTouchedTables.add(table);
                return delegate.getPartitionRows(table);
            }
        }
    }

    /** 注入故障：事务内写操作日志行（uuid=op:...）失败。 */
    static class LogWriteFailingTablestore extends TxScopeRecordingTablestore {

        LogWriteFailingTablestore() {
            super(row -> String.valueOf(row.getPrimaryKey().get("uuid")).startsWith("op:"));
        }
    }

    /** 注入故障：事务外对 doc_data_history 的 putRow 一律失败。 */
    static class HistoryWriteFailingTablestore extends TxScopeRecordingTablestore {

        @Override
        public void putRow(String table, TsRow row) {
            if ("doc_data_history".equals(table)) {
                throw new TablestoreAccessException("注入故障: history 投影失败");
            }
            super.putRow(table, row);
        }

        @Override
        public void putRow(String table, TsRow row, RowCondition condition) {
            if ("doc_data_history".equals(table)) {
                throw new TablestoreAccessException("注入故障: history 投影失败");
            }
            super.putRow(table, row, condition);
        }
    }
}
