package com.example.runtime.infrastructure.tablestore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.port.UniqueIndexStore;

/**
 * unique 约束伴生表 Tablestore 实现（unique_index，design/03 §五）。
 *
 * <p>主键：partition_key={@code {target_code}_{attr_code}_{hash4(value)}} +
 * unique_value（原文值，hash 撞位不串值）；占位 PutRow + EXPECT_NOT_EXIST 单行 CAS，
 * 冲突抛 {@link TsConditionCheckException} 映射为 false。逻辑删不释放
 * （design/03 §5.6，leader 裁决），释放仅限新增回滚与换值成功后旧值。</p>
 */
@Component
public class TablestoreUniqueIndexStore implements UniqueIndexStore {

    static final String TABLE = "unique_index";

    private final TablestoreTemplate template;

    public TablestoreUniqueIndexStore(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public boolean tryReserve(String targetCode, String attrCode, String value, String ownerUuid) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("owner_uuid", TsColumnValue.of(ownerUuid));
        // 冗余目标列（对账任务按列解析，避免分区键下划线歧义；只增不改）
        columns.put("target_code", TsColumnValue.of(targetCode));
        columns.put("attr_code", TsColumnValue.of(attrCode));
        columns.put("create_time", TsColumnValue.of(System.currentTimeMillis()));
        try {
            template.putRow(TABLE,
                    new TsRow(uniquePk(targetCode, attrCode, value), columns),
                    com.example.common.infrastructure.tablestore.model.RowCondition.expectNotExist());
            return true;
        } catch (TsConditionCheckException e) {
            // 行已存在：值被占用（含同 hash 同值既占行）
            return false;
        }
    }

    @Override
    public boolean release(String targetCode, String attrCode, String value, String expectedOwner) {
        try {
            template.deleteRow(TABLE, uniquePk(targetCode, attrCode, value),
                    com.example.common.infrastructure.tablestore.model.RowCondition
                            .expectExistAndColumnEq("owner_uuid", TsColumnValue.of(expectedOwner)));
            return true;
        } catch (TsConditionCheckException e) {
            // 占位不存在或已切换属主：迟到清理必须放弃，避免误删新 owner
            return false;
        }
    }

    @Override
    public Optional<String> findOwner(String targetCode, String attrCode, String value) {
        return template.getRow(TABLE, uniquePk(targetCode, attrCode, value))
                .map(row -> row.getString("owner_uuid"));
    }

    private static TsPrimaryKey uniquePk(String targetCode, String attrCode, String value) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.uniqueIndexKey(targetCode, attrCode, value))
                .add("unique_value", value)
                .build();
    }
}
