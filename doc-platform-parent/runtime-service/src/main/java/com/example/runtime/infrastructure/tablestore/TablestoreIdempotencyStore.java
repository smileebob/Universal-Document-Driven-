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
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.port.IdempotencyStore;

/**
 * 接口幂等记录 Tablestore 实现（idempotent_record，design/05 §5.2，B-01 变通）。
 *
 * <p>主键：partition_key={@code idem_{hash4(key)}} + idempotency_key；
 * 列：api_path / status(PROCESSING|DONE) / response_snapshot / error_snapshot /
 * create_time；表级 TTL 24h 由表定义承载。占位以 PutRow + EXPECT_NOT_EXIST
 * 单行 CAS，冲突回读既有记录供重放裁决。</p>
 */
@Component
public class TablestoreIdempotencyStore implements IdempotencyStore {

    static final String TABLE = "idempotent_record";

    private final TablestoreTemplate template;

    public TablestoreIdempotencyStore(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<Record> tryBegin(String key, String apiPath) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("api_path", TsColumnValue.of(apiPath));
        columns.put("status", TsColumnValue.of(STATUS_PROCESSING));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        try {
            template.putRow(TABLE, new TsRow(idemPk(key), columns), RowCondition.expectNotExist());
            return Optional.empty();
        } catch (TsConditionCheckException e) {
            // 键已存在：回读既有记录交由上层按状态重放
            return template.getRow(TABLE, idemPk(key)).map(this::mapRecord);
        }
    }

    @Override
    public void complete(String key, String apiPath, String responseSnapshot, String errorSnapshot) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put("status", TsColumnValue.of(STATUS_DONE)));
        updates.add(snapshotUpdate("response_snapshot", responseSnapshot));
        updates.add(snapshotUpdate("error_snapshot", errorSnapshot));
        template.updateRow(TABLE, idemPk(key), updates, RowCondition.ignore());
    }

    /** 快照列：有值写入、无值删列（避免 PROCESSING 占位列残留干扰重放） */
    private ColumnUpdate snapshotUpdate(String name, String value) {
        return value == null ? ColumnUpdate.delete(name) : ColumnUpdate.put(name, TsColumnValue.of(value));
    }

    private Record mapRecord(TsRow row) {
        return new Record(String.valueOf(row.getPrimaryKey().get("idempotency_key")),
                row.getString("api_path"), row.getString("status"),
                row.getString("response_snapshot"), row.getString("error_snapshot"));
    }

    private static TsPrimaryKey idemPk(String key) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.idemKey(key))
                .add("idempotency_key", key)
                .build();
    }
}
