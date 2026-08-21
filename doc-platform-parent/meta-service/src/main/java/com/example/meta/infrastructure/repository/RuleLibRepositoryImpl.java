package com.example.meta.infrastructure.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.meta.domain.doc.RuleLibEntry;

/**
 * 规则库仓储实现（rule_lib HASH4 分区表，RE-01/02）。
 *
 * <p>复合主键：partition_key={@code rule_{hash4(rule_uuid)}} + rule_uuid；
 * enabled 布尔以 status 列 1/0 存储（V1 多元索引字段名对齐）。</p>
 */
@Component
public class RuleLibRepositoryImpl implements RuleLibEntry.Repository {

    /** enabled=1 / disabled=0（rule_lib_index.status 字段口径） */
    private static final long ENABLED = 1L;
    private static final long DISABLED = 0L;

    private final TablestoreTemplate template;

    public RuleLibRepositoryImpl(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<RuleLibEntry> findByUuid(String uuid) {
        return template.getRow(MetaTableNames.RULE_LIB, pk(uuid)).map(this::toEntry);
    }

    @Override
    public void insert(RuleLibEntry entry) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_RULE_NAME, TsColumnValue.of(entry.getRuleName()));
        columns.put(MetaTableNames.COL_RULE_TYPE, TsColumnValue.of(entry.getRuleType()));
        putIfPresent(columns, MetaTableNames.COL_EXPRESSION, entry.getExpression());
        putIfPresent(columns, MetaTableNames.COL_INTERFACE_CODE, entry.getInterfaceCode());
        columns.put(MetaTableNames.COL_STATUS, TsColumnValue.of(entry.isEnabled() ? ENABLED : DISABLED));
        putIfPresent(columns, MetaTableNames.COL_DESCRIPTION, entry.getDescription());
        columns.put(MetaTableNames.COL_VERSION, TsColumnValue.of(entry.getVersion()));
        putIfPresent(columns, MetaTableNames.COL_CREATOR, entry.getCreator());
        if (entry.getCreateTime() != null) {
            columns.put(MetaTableNames.COL_CREATE_TIME, TsColumnValue.of(entry.getCreateTime()));
        }
        if (entry.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(entry.getUpdateTime()));
        }
        // EXPECT_NOT_EXIST 幂等建行；重复 uuid 抛 TsConditionCheckException（上层映射 40006）
        template.putRowIfAbsent(MetaTableNames.RULE_LIB, new TsRow(pk(entry.getUuid()), columns));
    }

    @Override
    public void updateWithVersion(RuleLibEntry entry) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put(MetaTableNames.COL_RULE_NAME, TsColumnValue.of(entry.getRuleName())));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_RULE_TYPE, TsColumnValue.of(entry.getRuleType())));
        updates.add(textColumn(MetaTableNames.COL_EXPRESSION, entry.getExpression()));
        updates.add(textColumn(MetaTableNames.COL_INTERFACE_CODE, entry.getInterfaceCode()));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_STATUS,
                TsColumnValue.of(entry.isEnabled() ? ENABLED : DISABLED)));
        updates.add(textColumn(MetaTableNames.COL_DESCRIPTION, entry.getDescription()));
        if (entry.getUpdateTime() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_UPDATE_TIME,
                    TsColumnValue.of(entry.getUpdateTime())));
        }
        template.updateWithVersion(MetaTableNames.RULE_LIB, pk(entry.getUuid()),
                entry.getVersion(), updates);
    }

    @Override
    public void deleteByUuidIfVersion(String uuid, long expectVersion) {
        // 条件删除：EXPECT_EXIST + version 列等值（读取快照 CAS）→ 并发时 40901
        template.deleteRow(MetaTableNames.RULE_LIB, pk(uuid),
                RowCondition.expectExistAndColumnEq(MetaTableNames.COL_VERSION,
                        TsColumnValue.of(expectVersion)));
    }

    @Override
    public List<RuleLibEntry> findAll() {
        List<TsRow> rows = template.getRange(MetaTableNames.RULE_LIB);
        List<RuleLibEntry> result = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            result.add(toEntry(row));
        }
        return result;
    }

    // ==================== 映射 ====================

    private static TsPrimaryKey pk(String uuid) {
        return TsPrimaryKey.builder()
                .add(MetaTableNames.PK_PARTITION_KEY, PartitionKeys.ruleKey(uuid))
                .add(MetaTableNames.PK_RULE_UUID, uuid)
                .build();
    }

    private RuleLibEntry toEntry(TsRow row) {
        RuleLibEntry entry = new RuleLibEntry();
        entry.setUuid(String.valueOf(row.getPrimaryKey().get(MetaTableNames.PK_RULE_UUID)));
        entry.setRuleName(row.getString(MetaTableNames.COL_RULE_NAME));
        entry.setRuleType(row.getString(MetaTableNames.COL_RULE_TYPE));
        entry.setExpression(row.getString(MetaTableNames.COL_EXPRESSION));
        entry.setInterfaceCode(row.getString(MetaTableNames.COL_INTERFACE_CODE));
        Long status = row.getLong(MetaTableNames.COL_STATUS);
        entry.setEnabled(status == null || status == ENABLED);
        entry.setDescription(row.getString(MetaTableNames.COL_DESCRIPTION));
        entry.setVersion(row.getLong(MetaTableNames.COL_VERSION) == null
                ? 0L : row.getLong(MetaTableNames.COL_VERSION));
        entry.setCreator(row.getString(MetaTableNames.COL_CREATOR));
        entry.setCreateTime(row.getLong(MetaTableNames.COL_CREATE_TIME));
        entry.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
        return entry;
    }

    private static void putIfPresent(Map<String, TsColumnValue> columns, String name, String value) {
        if (value != null) {
            columns.put(name, TsColumnValue.of(value));
        }
    }

    private static ColumnUpdate textColumn(String name, String value) {
        return value == null ? ColumnUpdate.delete(name) : ColumnUpdate.put(name, TsColumnValue.of(value));
    }
}
