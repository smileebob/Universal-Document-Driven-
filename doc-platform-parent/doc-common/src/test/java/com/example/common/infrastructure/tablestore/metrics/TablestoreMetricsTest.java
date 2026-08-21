package com.example.common.infrastructure.tablestore.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tablestore 防腐层指标测试（任务 #20，design/11 §4.2 / J-03）：
 * <ul>
 *   <li>{@link TablestoreMetrics} 成功/失败计数与延迟 Timer；NOOP 空实现不抛异常；</li>
 *   <li>{@link InstrumentedTablestoreTemplate} 装饰器按操作类型打点、异常原样上抛
 *       且计 result=error、未装配 registry 时安全降级。</li>
 * </ul>
 */
class TablestoreMetricsTest {

    @Test
    @DisplayName("record 成功：Timer count+1 且 counter result=success")
    void record_success_timerAndCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TablestoreMetrics metrics = new TablestoreMetrics(registry);

        metrics.record("get_row", System.nanoTime() - 1_000_000L, true);

        assertThat(registry.get("ddp_tablestore_operation_seconds")
                .tag("operation", "get_row").timer().count()).isEqualTo(1);
        assertThat(registry.get("ddp_tablestore_operation_total")
                .tags("operation", "get_row", "result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(metrics.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("record 失败：counter result=error（延迟仍入分布）")
    void record_error_counterTagged() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TablestoreMetrics metrics = new TablestoreMetrics(registry);

        metrics.record("put_row", System.nanoTime(), false);

        assertThat(registry.get("ddp_tablestore_operation_total")
                .tags("operation", "put_row", "result", "error").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("ddp_tablestore_operation_seconds")
                .tag("operation", "put_row").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOOP：无 registry 时 record 不抛异常且 isEnabled=false")
    void noop_record_noException() {
        assertThat(TablestoreMetrics.NOOP.isEnabled()).isFalse();
        TablestoreMetrics.NOOP.record("search", System.nanoTime(), true);
    }

    @Test
    @DisplayName("装饰器：put_row/get_row 按操作类型分别打点，getDelegate 暴露原始实现")
    void instrumentedTemplate_putAndGet_countedByOperation() {
        InMemoryTablestore delegate = new InMemoryTablestore();
        new TablestoreBootstrap(delegate).run();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InstrumentedTablestoreTemplate template = new InstrumentedTablestoreTemplate(
                delegate, new TablestoreMetrics(registry));

        TsPrimaryKey pk = TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey("po", "uuid-metrics-1"))
                .add("event_id", "ev-1").build();
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("send_status", TsColumnValue.of(0L));
        template.putRow("event_outbox", new TsRow(pk, columns));
        assertThat(template.getRow("event_outbox", pk)).isPresent();

        assertThat(registry.get("ddp_tablestore_operation_total")
                .tags("operation", "put_row", "result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("ddp_tablestore_operation_total")
                .tags("operation", "get_row", "result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(template.getDelegate()).isSameAs(delegate);
    }

    @Test
    @DisplayName("装饰器：委托异常原样上抛且计 result=error")
    void instrumentedTemplate_exception_rethrownAndCountedError() {
        TablestoreTemplate failing = mock(TablestoreTemplate.class);
        when(failing.getRow(anyString(), any(TsPrimaryKey.class)))
                .thenThrow(new IllegalStateException("boom"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InstrumentedTablestoreTemplate template = new InstrumentedTablestoreTemplate(
                failing, new TablestoreMetrics(registry));

        assertThatThrownBy(() -> template.getRow("doc_data",
                TsPrimaryKey.builder().add("partition_key", "x").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(registry.get("ddp_tablestore_operation_total")
                .tags("operation", "get_row", "result", "error").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("装饰器：metrics 传 null 走 NOOP，数据面操作不受影响")
    void instrumentedTemplate_nullMetrics_noop() {
        InMemoryTablestore delegate = new InMemoryTablestore();
        new TablestoreBootstrap(delegate).run();
        InstrumentedTablestoreTemplate template =
                new InstrumentedTablestoreTemplate(delegate, null);

        Optional<TsRow> row = template.getRow("event_outbox",
                TsPrimaryKey.builder().add("partition_key", PartitionKeys.dataKey("po", "uuid-none")).build());
        assertThat(row).isEmpty();
    }
}
