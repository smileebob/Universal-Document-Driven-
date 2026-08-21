package com.example.runtime;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.model.EntityDataRecord;
import com.example.runtime.domain.port.EntityDataRepository;
import com.example.runtime.infrastructure.reconcile.UniqueOrphanReconcileTask;
import com.example.runtime.infrastructure.tablestore.TablestoreUniqueIndexStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * unique_index 孤儿行对账任务测试（design/03 §5.6 全量扫描 + 只删不增）。
 *
 * <p>覆盖：物理孤儿超 10 分钟释放（含同分区 reconcile 审计）、新鲜窗口跳过、
 * 属主有效跳过、逻辑删超 1 小时宽限释放 / 宽限内跳过、doc_data 兜底反查、
 * 并发触发 40901、isOrphan 单点判定、分区键解析。</p>
 */
class UniqueOrphanReconcileTaskTest {

    private InMemoryTablestore template;
    private TablestoreUniqueIndexStore indexStore;
    private EntityDataRepository entityRepository;
    private UniqueOrphanReconcileTask task;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        indexStore = new TablestoreUniqueIndexStore(template);
        entityRepository = mock(EntityDataRepository.class);
        task = new UniqueOrphanReconcileTask(indexStore, entityRepository, template, false);
    }

    // ==================== 数据播种工具 ====================

    /** 直接播种 unique_index 占位行（可控 create_time） */
    private void seedUnique(String target, String attr, String value, String owner, long createTime) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("owner_uuid", TsColumnValue.of(owner));
        columns.put("create_time", TsColumnValue.of(createTime));
        columns.put("target_code", TsColumnValue.of(target));
        columns.put("attr_code", TsColumnValue.of(attr));
        template.putRow("unique_index", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.uniqueIndexKey(target, attr, value))
                .add("unique_value", value)
                .build(), columns));
    }

    /** 播种 entity_data 主行（可控 status / update_time） */
    private void seedEntity(String code, String uuid, long status, long updateTime) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("status", TsColumnValue.of(status));
        columns.put("create_time", TsColumnValue.of(updateTime));
        columns.put("update_time", TsColumnValue.of(updateTime));
        columns.put("version", TsColumnValue.of(1L));
        template.putRow("entity_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(code, uuid))
                .add("uuid", uuid)
                .build(), columns));
    }

    /** 播种 doc_data 主行（可控 deleted / update_time） */
    private void seedDoc(String code, String uuid, long deleted, long updateTime) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("deleted", TsColumnValue.of(deleted));
        columns.put("create_time", TsColumnValue.of(updateTime));
        columns.put("update_time", TsColumnValue.of(updateTime));
        columns.put("version", TsColumnValue.of(1L));
        template.putRow("doc_data", new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(code, uuid))
                .add("uuid", uuid)
                .build(), columns));
    }

    // ==================== 主场景 ====================

    @Test
    @DisplayName("物理孤儿（主行缺失 >10min）→ 释放占位 + reconcile 审计落库")
    void physicalOrphan_beyondFreshWindow_released() {
        long now = System.currentTimeMillis();
        seedUnique("customer", "email", "a@x.com", "orphan-uuid",
                now - TimeUnit.MINUTES.toMillis(11));

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("released", 1L)
                .containsEntry("scanned", 1L).containsEntry("errors", 0L);
        assertThat(indexStore.findOwner("customer", "email", "a@x.com")).isEmpty();
        // 同目标分区对账审计（operation=reconcile，operator=system）
        assertThat(template.getRange("audit_log")).anyMatch(row ->
                "reconcile".equals(row.getString("operation"))
                        && "system".equals(row.getString("operator"))
                        && "customer".equals(row.getString("target_code")));
    }

    @Test
    @DisplayName("新鲜窗口内（<10min）主行缺失 → 跳过（容忍事务在途）")
    void freshPlaceholder_skipped() {
        seedUnique("customer", "email", "b@x.com", "fresh-uuid", System.currentTimeMillis());

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("skipped_fresh", 1L).containsEntry("released", 0L);
        assertThat(indexStore.findOwner("customer", "email", "b@x.com")).contains("fresh-uuid");
    }

    @Test
    @DisplayName("属主 entity 主行有效（status=1）→ 跳过")
    void activeEntity_skipped() {
        long now = System.currentTimeMillis();
        seedUnique("customer", "email", "c@x.com", "owner-1",
                now - TimeUnit.MINUTES.toMillis(30));
        seedEntity("customer", "owner-1", 1L, now);

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("skipped_active", 1L).containsEntry("released", 0L);
        assertThat(indexStore.findOwner("customer", "email", "c@x.com")).contains("owner-1");
    }

    @Test
    @DisplayName("逻辑删 entity（status=0）超 1h 宽限 → 释放")
    void deletedEntity_beyondGrace_released() {
        long now = System.currentTimeMillis();
        seedUnique("customer", "email", "d@x.com", "owner-2",
                now - TimeUnit.HOURS.toMillis(3));
        seedEntity("customer", "owner-2", 0L, now - TimeUnit.HOURS.toMillis(2));

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("released", 1L);
        assertThat(indexStore.findOwner("customer", "email", "d@x.com")).isEmpty();
    }

    @Test
    @DisplayName("逻辑删 entity 宽限内（<1h）→ 跳过保留占位")
    void deletedEntity_withinGrace_skipped() {
        long now = System.currentTimeMillis();
        seedUnique("customer", "email", "e@x.com", "owner-3",
                now - TimeUnit.HOURS.toMillis(3));
        seedEntity("customer", "owner-3", 0L, now);

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("skipped_fresh", 1L).containsEntry("released", 0L);
        assertThat(indexStore.findOwner("customer", "email", "e@x.com")).contains("owner-3");
    }

    @Test
    @DisplayName("doc_data 兜底反查：逻辑删单据超宽限 → 释放")
    void docRow_deletedBeyondGrace_released() {
        long now = System.currentTimeMillis();
        seedUnique("po", "order_no", "PO-2026-001", "doc-1",
                now - TimeUnit.HOURS.toMillis(3));
        seedDoc("po", "doc-1", 1L, now - TimeUnit.HOURS.toMillis(2));

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("released", 1L);
        assertThat(indexStore.findOwner("po", "order_no", "PO-2026-001")).isEmpty();
    }

    @Test
    @DisplayName("doc_data 未删除 → 属主有效跳过")
    void docRow_active_skipped() {
        long now = System.currentTimeMillis();
        seedUnique("po", "order_no", "PO-2026-002", "doc-2",
                now - TimeUnit.HOURS.toMillis(3));
        seedDoc("po", "doc-2", 0L, now);

        Map<String, Object> summary = task.triggerManual();

        assertThat(summary).containsEntry("skipped_active", 1L).containsEntry("released", 0L);
    }

    // ==================== 并发防护 / 单点判定 / 解析 ====================

    @Test
    @DisplayName("并发触发：执行中再触发 → 40901；完成后恢复正常")
    void concurrentTrigger_40901() throws Exception {
        Field runningField = UniqueOrphanReconcileTask.class.getDeclaredField("running");
        runningField.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) runningField.get(task);
        running.set(true);
        try {
            assertThatThrownBy(() -> task.triggerManual())
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(40901));
        } finally {
            running.set(false);
        }
        // 释放后恢复正常触发
        assertThat(task.triggerManual()).containsEntry("scanned", 0L);
    }

    @Test
    @DisplayName("定时开关关闭时 scheduledReconcile 直接跳过")
    void scheduled_disabled_skipped() {
        seedUnique("customer", "email", "f@x.com", "orphan-2",
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11));
        task.scheduledReconcile();
        // 开关 false：未执行，占位仍在
        assertThat(indexStore.findOwner("customer", "email", "f@x.com")).contains("orphan-2");
    }

    @Test
    @DisplayName("isOrphan 单点判定：主行缺失=孤儿 / 主行存在或无占位=false")
    void isOrphan_singlePointJudgement() {
        seedUnique("customer", "email", "g@x.com", "ghost", System.currentTimeMillis());
        when(entityRepository.findByKey(anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(task.isOrphan("customer", "email", "g@x.com")).isTrue();

        when(entityRepository.findByKey(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EntityDataRecord.class)));
        assertThat(task.isOrphan("customer", "email", "g@x.com")).isFalse();
        assertThat(task.isOrphan("customer", "email", "none@x.com")).isFalse();
    }

}
