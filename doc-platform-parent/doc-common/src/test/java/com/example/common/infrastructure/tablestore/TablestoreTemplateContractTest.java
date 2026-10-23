package com.example.common.infrastructure.tablestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TsCrossPartitionException;
import com.example.common.infrastructure.tablestore.exception.TsCrossTableException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.model.BatchWriteResult;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowChange;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TablestoreTemplate 门面契约验收测试（全部跑在 InMemoryTablestore 上）：
 * 条件更新（EXPECT_NOT_EXIST 成功/冲突）、version CAS、局部事务（同分区单表原子/
 * 跨分区拒绝/跨表拒绝——OTS 表级作用域硬约束对齐）、
 * batch 分段（≤100/≤200）、大行拦截、分区键红线。
 */
class TablestoreTemplateContractTest {

    private static final String DOC_DATA = "doc_data";
    private static final String DOC_HISTORY = "doc_data_history";
    private static final String EVENT_OUTBOX = "event_outbox";

    private InMemoryTablestore template;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        // 经 Bootstrap 加载 V1+V2 定义并注册元数据（仿真侧仅注册，不建云表）
        List<TableDefinition> definitions =
                new TablestoreBootstrap(template).run();
        assertThat(definitions).hasSize(18);
        assertThat(definitions).extracting(TableDefinition::getName)
                .contains("event_delivery_cursor", "index_publish_task");
    }

    private TsPrimaryKey docPk(String uuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("sales_order", uuid))
                .add("uuid", uuid)
                .build();
    }

    private TsRow docRow(TsPrimaryKey pk, long version) {
        return new TsRow(pk, Map.of(
                "doc_code", TsColumnValue.of("sales_order"),
                "current_status", TsColumnValue.of(100L),
                "version", TsColumnValue.of(version)));
    }

    // ==================== 分区级范围扫描（RD-08 status_history 读路径） ====================

    @Test
    @DisplayName("getPartitionRange：仅返回本分区行且主键序升序（seq 乱序写入仍有序）")
    void partitionRange_scopedAndOrdered() {
        String uuidA = UUID.randomUUID().toString();
        String uuidB = UUID.randomUUID().toString();
        String pkA = PartitionKeys.dataKey("sales_order", uuidA);
        // 同分区两条历史行（seq=2 先写、seq=1 后写，验证排序口径）
        template.putRow(DOC_HISTORY, partitionHistoryRow(pkA, uuidA, 2L));
        template.putRow(DOC_HISTORY, partitionHistoryRow(pkA, uuidA, 1L));
        // 异分区行不得入列
        template.putRow(DOC_HISTORY, partitionHistoryRow(
                PartitionKeys.dataKey("sales_order", uuidB), uuidB, 1L));

        List<TsRow> rows = template.getPartitionRange(DOC_HISTORY, pkA);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.getPrimaryKey().get("seq"))
                .containsExactly(1L, 2L);
    }

    private TsRow partitionHistoryRow(String partitionKey, String uuid, long seq) {
        return new TsRow(TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("uuid", uuid)
                .add("seq", seq)
                .build(), Map.of("to_status", TsColumnValue.of(100L)));
    }

    @Test
    @DisplayName("search：putRow 后立即可检索（仿真层无异步索引，写入即见，E2E 缺陷3 语义固化）")
    void search_immediatelyVisible_afterPutRow() {
        String uuid = UUID.randomUUID().toString();
        template.putRow(DOC_DATA, docRow(docPk(uuid), 1));

        SearchResult result = template.search(DOC_DATA, "doc_data_index",
                QuerySpec.builder()
                        .term("doc_code", TsColumnValue.of("sales_order"))
                        .limit(10)
                        .getTotalCount(true)
                        .build());

        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getPrimaryKey().get("uuid")).isEqualTo(uuid);
    }

    // ==================== 条件更新 ====================

    @Test
    @DisplayName("EXPECT_NOT_EXIST 条件写入：首次成功，重复写入抛 TsConditionCheckException")
    void putRowIfAbsent_success_then_conflict() {
        TsPrimaryKey pk = docPk(UUID.randomUUID().toString());
        template.putRowIfAbsent(DOC_DATA, docRow(pk, 1));
        assertThat(template.getRow(DOC_DATA, pk)).isPresent();

        assertThatThrownBy(() -> template.putRowIfAbsent(DOC_DATA, docRow(pk, 1)))
                .isInstanceOf(TsConditionCheckException.class)
                .hasMessageContaining("EXPECT_NOT_EXIST");
    }

    @Test
    @DisplayName("EXPECT_EXIST 条件更新：行不存在时拒绝")
    void updateRow_expectExist_missingRow() {
        TsPrimaryKey pk = docPk(UUID.randomUUID().toString());
        assertThatThrownBy(() -> template.updateRow(DOC_DATA, pk,
                List.of(ColumnUpdate.put("current_status", TsColumnValue.of(10L))),
                RowCondition.expectExist()))
                .isInstanceOf(TsConditionCheckException.class);
    }

    // ==================== version CAS（TS-04） ====================

    @Test
    @DisplayName("updateWithVersion：版本匹配成功并 +1，版本过期抛条件异常")
    void updateWithVersion_cas() {
        String uuid = UUID.randomUUID().toString();
        TsPrimaryKey pk = docPk(uuid);
        template.putRow(DOC_DATA, docRow(pk, 1));

        template.updateWithVersion(DOC_DATA, pk, 1L,
                List.of(ColumnUpdate.put("current_status", TsColumnValue.of(10L))));
        TsRow updated = template.getRow(DOC_DATA, pk).orElseThrow();
        assertThat(updated.getLong("version")).isEqualTo(2L);
        assertThat(updated.getLong("current_status")).isEqualTo(10L);

        // 并发场景：另一方仍持旧 version=1 → 冲突
        assertThatThrownBy(() -> template.updateWithVersion(DOC_DATA, pk, 1L,
                List.of(ColumnUpdate.put("current_status", TsColumnValue.of(20L)))))
                .isInstanceOf(TsConditionCheckException.class);
        // 数据未被污染
        assertThat(template.getRow(DOC_DATA, pk).orElseThrow().getLong("current_status")).isEqualTo(10L);
    }

    // ==================== 局部事务 ====================

    @Test
    @DisplayName("局部事务：单表原子提交成功（仅 anchor 表 doc_data CAS 更新）")
    void localTransaction_singleTable_atomicCommit() {
        String uuid = UUID.randomUUID().toString();
        String partitionKey = PartitionKeys.dataKey("sales_order", uuid);
        TsPrimaryKey docPk = docPk(uuid);
        template.putRow(DOC_DATA, docRow(docPk, 1));

        String result = template.executeInLocalTransaction(DOC_DATA, partitionKey, ops -> {
            ops.updateRow(DOC_DATA, docPk,
                    List.of(ColumnUpdate.put("current_status", TsColumnValue.of(10L)),
                            ColumnUpdate.put("version", TsColumnValue.of(2L))),
                    RowCondition.expectExistAndColumnEq("version", TsColumnValue.of(1L)));
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(template.getRow(DOC_DATA, docPk).orElseThrow().getLong("current_status")).isEqualTo(10L);
        assertThat(template.getRow(DOC_DATA, docPk).orElseThrow().getLong("version")).isEqualTo(2L);
    }

    @Test
    @DisplayName("局部事务：跨表写被拒（OTS 表级作用域硬约束，仿真提前拦截）")
    void localTransaction_crossTable_rejected() {
        String uuid = UUID.randomUUID().toString();
        String partitionKey = PartitionKeys.dataKey("sales_order", uuid);
        TsPrimaryKey historyPk = TsPrimaryKey.builder()
                .add("partition_key", partitionKey).add("uuid", uuid).add("seq", 1L).build();
        TsPrimaryKey outboxPk = TsPrimaryKey.builder()
                .add("partition_key", partitionKey).add("event_id", "evt_001").build();

        assertThatThrownBy(() -> template.executeInLocalTransaction(DOC_DATA, partitionKey, ops -> {
            ops.putRow(DOC_HISTORY, new TsRow(historyPk, Map.of("to_status", TsColumnValue.of(10L))));
            return null;
        })).isInstanceOf(TsCrossTableException.class)
                .hasMessageContaining("doc_data");

        // 跨表读/分区扫描同样被拒（真实 OTS TxGetRow/TxGetRange 亦报 OTSSessionNotExist）
        assertThatThrownBy(() -> template.executeInLocalTransaction(DOC_DATA, partitionKey, ops -> {
            return ops.getPartitionRows(DOC_HISTORY);
        })).isInstanceOf(TsCrossTableException.class);
        assertThatThrownBy(() -> template.executeInLocalTransaction(DOC_DATA, partitionKey, ops -> {
            return ops.getRow(EVENT_OUTBOX, outboxPk);
        })).isInstanceOf(TsCrossTableException.class);

        // 事务被拒后无残留写
        assertThat(template.getRow(DOC_HISTORY, historyPk)).isEmpty();
    }

    @Test
    @DisplayName("局部事务：事务体抛异常时全部回滚（无可见写）")
    void localTransaction_rollback_onException() {
        String uuid = UUID.randomUUID().toString();
        String partitionKey = PartitionKeys.dataKey("sales_order", uuid);
        TsPrimaryKey pk = docPk(uuid);

        assertThatThrownBy(() -> template.executeInLocalTransaction(DOC_DATA, partitionKey, ops -> {
            ops.putRow(DOC_DATA, docRow(pk, 1));
            throw new BizException(ErrorCode.RULE_FAILED, "规则校验未通过");
        })).isInstanceOf(BizException.class);

        assertThat(template.getRow(DOC_DATA, pk)).isEmpty();
    }

    @Test
    @DisplayName("局部事务：事务内可读自身未提交写；跨分区操作抛 TsCrossPartitionException")
    void localTransaction_readOwnWrites_and_crossPartitionRejected() {
        String uuid = UUID.randomUUID().toString();
        String partitionKey = PartitionKeys.dataKey("sales_order", uuid);
        TsPrimaryKey pk = docPk(uuid);

        template.executeInLocalTransaction(DOC_DATA, partitionKey, ops -> {
            ops.putRow(DOC_DATA, docRow(pk, 1));
            Optional<TsRow> readBack = ops.getRow(DOC_DATA, pk);
            assertThat(readBack).isPresent();
            assertThat(readBack.orElseThrow().getLong("current_status")).isEqualTo(100L);

            TsPrimaryKey otherPartition = docPk(UUID.randomUUID().toString());
            assertThatThrownBy(() -> ops.putRow(DOC_DATA, docRow(otherPartition, 1)))
                    .isInstanceOf(TsCrossPartitionException.class);
            return null;
        });
    }

    // ==================== 批量分段（TS-02/TS-03） ====================

    @Test
    @DisplayName("batchGet：250 个主键自动按 100 分段，全部命中")
    void batchGet_chunking() {
        List<TsPrimaryKey> pks = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            TsPrimaryKey pk = docPk(UUID.randomUUID().toString());
            pks.add(pk);
            template.putRow(DOC_DATA, docRow(pk, 1));
        }
        assertThat(template.batchGet(DOC_DATA, pks)).hasSize(250);
        assertThat(template.batchGet(DOC_DATA, List.of())).isEmpty();
    }

    @Test
    @DisplayName("batchWrite：450 行自动按 200 分段全部成功；空列表返回零结果")
    void batchWrite_chunking() {
        List<RowChange> changes = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            changes.add(RowChange.put(DOC_DATA, docRow(docPk(UUID.randomUUID().toString()), 1)));
        }
        BatchWriteResult result = template.batchWrite(changes);
        assertThat(result.getTotal()).isEqualTo(450);
        assertThat(result.isAllSucceeded()).isTrue();
        assertThat(template.batchWrite(List.of()).getTotal()).isZero();
    }

    @Test
    @DisplayName("batchWrite 携带 UPDATE/DELETE 混合操作")
    void batchWrite_mixedOps() {
        TsPrimaryKey pk = docPk(UUID.randomUUID().toString());
        template.putRow(DOC_DATA, docRow(pk, 1));

        BatchWriteResult result = template.batchWrite(List.of(
                RowChange.update(DOC_DATA, pk,
                        List.of(ColumnUpdate.put("current_status", TsColumnValue.of(20L))),
                        RowCondition.ignore()),
                RowChange.delete(DOC_DATA, pk)));
        assertThat(result.isAllSucceeded()).isTrue();
        assertThat(template.getRow(DOC_DATA, pk)).isEmpty();
    }

    // ==================== 写入侧红线拦截 ====================

    @Test
    @DisplayName("分区键红线：HASH 表不符合 {code}_{hashN} 格式拒绝写入")
    void writeGuard_partitionKeyFormat() {
        // hash 位数不符（3 位）
        TsPrimaryKey badHash = TsPrimaryKey.builder()
                .add("partition_key", "sales_order_abc").add("uuid", "u1").build();
        assertThatThrownBy(() -> template.putRow(DOC_DATA, docRow(badHash, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash 位数");

        // 数字/时间前缀（热点红线 1/2）
        TsPrimaryKey timePrefix = TsPrimaryKey.builder()
                .add("partition_key", "202608_a1b2").add("uuid", "u2").build();
        assertThatThrownBy(() -> template.putRow(DOC_DATA, docRow(timePrefix, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字/时间前缀");

        // PLAIN 配置表（entity_schema）code 直接作分区键，合法
        template.putRow("entity_schema", new TsRow(
                TsPrimaryKey.of("entity_code", "customer"),
                Map.of("entity_name", TsColumnValue.of("客户"))));
        assertThat(template.getRow("entity_schema", TsPrimaryKey.of("entity_code", "customer")))
                .isPresent();
    }

    @Test
    @DisplayName("大行防护：attributes > 1.5MB 抛 BizException(40002)；>256KB 告警但放行")
    void writeGuard_largeRow() {
        TsPrimaryKey pk = docPk(UUID.randomUUID().toString());

        String oversize = "x".repeat(WriteGuardBytes.MB_1_5 + 1);
        assertThatThrownBy(() -> template.putRow(DOC_DATA, new TsRow(pk,
                Map.of("attributes", TsColumnValue.of(oversize)))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TYPE_INVALID.getCode());

        String warnLevel = "x".repeat(WriteGuardBytes.KB_300);
        template.putRow(DOC_DATA, new TsRow(pk, Map.of("attributes", TsColumnValue.of(warnLevel))));
        assertThat(template.getRow(DOC_DATA, pk)).isPresent();
    }

    /** 大行测试体积常量 */
    private static final class WriteGuardBytes {
        static final int MB_1_5 = 1536 * 1024;
        static final int KB_300 = 300 * 1024;
    }

    // ==================== 删除与点查 ====================

    @Test
    @DisplayName("deleteRow 与 getRow 未命中语义")
    void delete_and_getMissing() {
        TsPrimaryKey pk = docPk(UUID.randomUUID().toString());
        template.putRow(DOC_DATA, docRow(pk, 1));
        template.deleteRow(DOC_DATA, pk);
        assertThat(template.getRow(DOC_DATA, pk)).isEmpty();
        assertThat(template.getRow("not_exist_table", pk)).isEmpty();
    }
    // ==================== getRange ====================

    @Test
    @DisplayName("getRange: full scan in primary key order; empty table returns empty list")
    void getRange_fullScan_inPrimaryKeyOrder() {
        template.putRow("entity_schema", new TsRow(
                TsPrimaryKey.of("entity_code", "customer"),
                Map.of("entity_name", TsColumnValue.of("customer-name"))));
        template.putRow("entity_schema", new TsRow(
                TsPrimaryKey.of("entity_code", "product"),
                Map.of("entity_name", TsColumnValue.of("product-name"))));
        template.putRow("entity_schema", new TsRow(
                TsPrimaryKey.of("entity_code", "address"),
                Map.of("entity_name", TsColumnValue.of("address-name"))));

        List<TsRow> rows = template.getRange("entity_schema");
        assertThat(rows).hasSize(3);
        assertThat(rows.stream()
                .map(row -> String.valueOf(row.getPrimaryKey().get("entity_code")))
                .toList())
                .containsExactly("address", "customer", "product");

        assertThat(template.getRange("rule_lib")).isEmpty();
    }
}