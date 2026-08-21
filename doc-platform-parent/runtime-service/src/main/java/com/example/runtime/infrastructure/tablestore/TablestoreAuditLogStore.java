package com.example.runtime.infrastructure.tablestore;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.port.AuditLogStore;

/**
 * 审计日志 Tablestore 实现（audit_log，只增不改，RD-12 / design/03 §2.10）。
 *
 * <p>主键：partition_key={@code log_{hash6(log_id)}} + log_id（禁时间前缀，
 * 三年 2000 万行打散）；列：operation/target_type/target_code/target_uuid/
 * operator/operate_time/before_value/after_value/ip/trace_id。</p>
 */
@Component
public class TablestoreAuditLogStore implements AuditLogStore {

    static final String TABLE = "audit_log";
    static final String INDEX = "audit_log_index";

    private final TablestoreTemplate template;

    public TablestoreAuditLogStore(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public void append(AuditEntry entry) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        putIfNotNull(columns, "operation", entry.getOperation());
        putIfNotNull(columns, "target_type", entry.getTargetType());
        putIfNotNull(columns, "target_code", entry.getTargetCode());
        putIfNotNull(columns, "target_uuid", entry.getTargetUuid());
        putIfNotNull(columns, "operator", entry.getOperator());
        columns.put("operate_time", TsColumnValue.of(entry.getOperateTime()));
        putIfNotNull(columns, "before_value", entry.getBeforeValue());
        putIfNotNull(columns, "after_value", entry.getAfterValue());
        putIfNotNull(columns, "ip", entry.getIp());
        putIfNotNull(columns, "trace_id", entry.getTraceId());
        TsPrimaryKey pk = TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.logKey(entry.getLogId()))
                .add("log_id", entry.getLogId())
                .build();
        template.putRow(TABLE, new TsRow(pk, columns));
    }

    @Override
    public SearchResult search(QuerySpec spec) {
        return template.search(TABLE, INDEX, spec);
    }

    private void putIfNotNull(Map<String, TsColumnValue> columns, String name, String value) {
        if (value != null) {
            columns.put(name, TsColumnValue.of(value));
        }
    }
}
