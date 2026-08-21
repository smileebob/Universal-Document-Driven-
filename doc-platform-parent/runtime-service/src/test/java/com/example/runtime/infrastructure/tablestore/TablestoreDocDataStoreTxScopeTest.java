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

import com.example.common.infrastructure.tablestore.LocalTransactionOps;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.exception.TablestoreAccessException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.port.DocDataStore.TransitionOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTS 局部事务表级作用域整改验证（TablestoreDocDataStore）。
 *
 * <p>真实 OTS 局部事务为表级作用域：事务 ID 仅可用于 anchorTable(doc_data)
 * 自身读写。本测试经 TxScopeRecordingTablestore 记录事务内实际触达的表，
 * 断言：创建/流转链路事务内仅含 doc_data 操作；history/outbox/审计均在
 * 事务提交后被写入；history 补写失败不阻断主流程返回。</p>
 */
class TablestoreDocDataStoreTxScopeTest {

    private static final String DOC = "po";

    private TxScopeRecordingTablestore template;
    private TablestoreDocDataStore store;

    @BeforeEach
    void setUp() {
        template = new TxScopeRecordingTablestore();
        new TablestoreBootstrap(template).run();
        store = new TablestoreDocDataStore(template, new ObjectMapper());
    }

    private DocDataRecord newRecord(String uuid) {
        long now = System.currentTimeMillis();
        return new DocDataRecord(uuid, DOC, 100L, 1L,
                Map.of("amount", 100), List.of(), false, "alice", "dept", now, now);
    }

    // ==================== 创建链路 ====================

    @Test
    @DisplayName("创建：事务内仅 doc_data 写入；history/outbox 在提交后被写入")
    void create_txScopeOnlyDocData_postCommitSideWrites() {
        String uuid = UUID.randomUUID().toString();
        store.create(newRecord(uuid), "alice", "trace-1");

        // 事务内触达表集合：只允许 anchor 表 doc_data（OTS 表级作用域硬约束）
        assertThat(template.touchedTablesInTx()).containsExactly("doc_data");

        // 主行落库（用户可见结果可靠）
        assertThat(store.find(DOC, uuid)).isPresent();
        // 提交后补写：创建历史行 seq=1 + DOC_CREATED outbox 行
        assertThat(store.findHistory(DOC, uuid)).hasSize(1);
        assertThat(store.findHistory(DOC, uuid).get(0).get("operation")).isEqualTo("create");
        assertThat(template.getPartitionRange("event_outbox",
                PartitionKeys.dataKey(DOC, uuid))).hasSize(1);
    }

    // ==================== 流转链路 ====================

    @Test
    @DisplayName("流转：事务内仅 doc_data 读写；history/outbox/审计在提交后被写入")
    void commitTransition_txScopeOnlyDocData_postCommitSideWrites() {
        String uuid = UUID.randomUUID().toString();
        store.create(newRecord(uuid), "alice", null);
        template.resetTxScope();

        TransitionOutcome outcome =
                store.commitTransition(DOC, uuid, 100L, 10L, "bob", "submit", null);

        // 事务内触达表集合：只允许 anchor 表 doc_data（含事务内 T1 GetRow 读）
        assertThat(template.touchedTablesInTx()).containsExactly("doc_data");
        assertThat(outcome.version()).isEqualTo(2L);
        assertThat(outcome.historySeq()).isEqualTo(2L);

        // 提交后补写：创建行 seq=1 + 流转行 seq=2
        assertThat(store.findHistory(DOC, uuid)).hasSize(2);
        // outbox：DOC_CREATED + DOC_STATUS_CHANGED 两行
        assertThat(template.getPartitionRange("event_outbox",
                PartitionKeys.dataKey(DOC, uuid))).hasSize(2);
        // 流转审计行落库（audit_log hash4 变体分区键与主行同分区键）
        assertThat(template.getPartitionRange("audit_log",
                PartitionKeys.dataKey(DOC, uuid))).hasSize(1);
    }

    // ==================== 补写失败不阻断 ====================

    @Test
    @DisplayName("创建：history 补写失败不影响主流程返回，主行仍可靠落库")
    void create_historyPostWriteFailure_doesNotBlockMainFlow() {
        HistoryWriteFailingTablestore failing = new HistoryWriteFailingTablestore();
        new TablestoreBootstrap(failing).run();
        TablestoreDocDataStore failingStore =
                new TablestoreDocDataStore(failing, new ObjectMapper());
        String uuid = UUID.randomUUID().toString();

        // 不抛异常即主流程不受补写失败影响（WARN 日志为可观测出口）
        failingStore.create(newRecord(uuid), "alice", null);

        assertThat(failingStore.find(DOC, uuid)).isPresent();
        assertThat(failingStore.findHistory(DOC, uuid)).isEmpty();
        assertThat(failing.touchedTablesInTx()).containsExactly("doc_data");
    }

    // ==================== seq 竞态整改（评审项 1） ====================

    @Test
    @DisplayName("seq=version 等价：多次流转 seq 序列 1,2,3 与 version 序列一致（替代事务外 maxSeq+1）")
    void historySeq_equalsVersion_acrossTransitions() {
        String uuid = UUID.randomUUID().toString();
        store.create(newRecord(uuid), "alice", null);

        TransitionOutcome first =
                store.commitTransition(DOC, uuid, 100L, 10L, "bob", "submit", null);
        TransitionOutcome second =
                store.commitTransition(DOC, uuid, 10L, 20L, "bob", "approve", null);

        // historySeq 与 version 同值：CAS 保护、天然单调唯一
        assertThat(first.version()).isEqualTo(2L);
        assertThat(first.historySeq()).isEqualTo(first.version());
        assertThat(second.version()).isEqualTo(3L);
        assertThat(second.historySeq()).isEqualTo(second.version());

        // 正常单线流转下 seq 序列 1,2,3… 与旧 maxSeq+1 口径等价
        List<Map<String, Object>> history = store.findHistory(DOC, uuid);
        assertThat(history).hasSize(3);
        assertThat(history).extracting(row -> row.get("seq"))
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("history 补写 expectNotExist：撞键宁可缺行走 WARN，不静默覆盖先写流水")
    void historyWrite_expectNotExist_neverOverwritesExistingRow() {
        String uuid = UUID.randomUUID().toString();
        store.create(newRecord(uuid), "alice", null);

        // 预插哨兵行：与下次流转（newVersion=2）补写行同主键 {partition_key,uuid,seq=2}
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

        // 主流程正常返回（条件冲突仅 WARN，不阻断；主行已在单表事务内可靠落库）
        TransitionOutcome outcome =
                store.commitTransition(DOC, uuid, 100L, 10L, "bob", "submit", null);
        assertThat(outcome.version()).isEqualTo(2L);
        assertThat(outcome.historySeq()).isEqualTo(2L);

        // 哨兵行内容未被覆盖（history 只增不改：缺行可告警人工补录，不可静默覆盖）
        List<Map<String, Object>> history = store.findHistory(DOC, uuid);
        assertThat(history).hasSize(2);
        assertThat(history).extracting(row -> String.valueOf(row.get("operation")))
                .containsExactly("create", "sentinel");
        assertThat(history.get(1).get("operator")).isEqualTo("first-writer");
    }

    // ==================== 测试桩：事务作用域记录 ====================

    /** 记录事务内 LocalTransactionOps 实际触达的表名（读/写均计） */
    static class TxScopeRecordingTablestore extends InMemoryTablestore {

        private final Set<String> txTouchedTables = new LinkedHashSet<>();

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
                delegate.putRow(table, row);
            }

            @Override
            public void putRow(String table, TsRow row, RowCondition condition) {
                txTouchedTables.add(table);
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

    /** 注入故障：事务外对 doc_data_history 的 putRow 一律失败（模拟补写瞬时故障） */
    static class HistoryWriteFailingTablestore extends TxScopeRecordingTablestore {

        @Override
        public void putRow(String table, TsRow row) {
            if ("doc_data_history".equals(table)) {
                throw new TablestoreAccessException("注入故障: history 补写失败");
            }
            super.putRow(table, row);
        }

        @Override
        public void putRow(String table, TsRow row, RowCondition condition) {
            if ("doc_data_history".equals(table)) {
                throw new TablestoreAccessException("注入故障: history 补写失败");
            }
            super.putRow(table, row, condition);
        }
    }
}
