package com.example.common.infrastructure.tablestore.ots;

import java.util.LinkedHashMap;
import java.util.Map;

import com.alicloud.openservices.tablestore.model.Column;
import com.alicloud.openservices.tablestore.model.ColumnValue;
import com.alicloud.openservices.tablestore.model.Condition;
import com.alicloud.openservices.tablestore.model.PrimaryKey;
import com.alicloud.openservices.tablestore.model.PrimaryKeyBuilder;
import com.alicloud.openservices.tablestore.model.PrimaryKeyColumn;
import com.alicloud.openservices.tablestore.model.PrimaryKeyValue;
import com.alicloud.openservices.tablestore.model.Row;
import com.alicloud.openservices.tablestore.model.RowExistenceExpectation;
import com.alicloud.openservices.tablestore.model.condition.SingleColumnValueCondition;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;

/**
 * 防腐层模型 ↔ Tablestore SDK 模型转换器（仅 ots 子包内部使用）。
 */
final class OtsTypes {

    private OtsTypes() {
    }

    static PrimaryKey toSdkPk(TsPrimaryKey pk) {
        PrimaryKeyBuilder builder = PrimaryKeyBuilder.createPrimaryKeyBuilder();
        for (Map.Entry<String, Object> entry : pk.getColumns().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                builder.addPrimaryKeyColumn(entry.getKey(), PrimaryKeyValue.fromString((String) value));
            } else {
                builder.addPrimaryKeyColumn(entry.getKey(), PrimaryKeyValue.fromLong((Long) value));
            }
        }
        return builder.build();
    }

    static ColumnValue toSdkColumnValue(TsColumnValue value) {
        switch (value.getType()) {
            case STRING:
                return ColumnValue.fromString(value.asString());
            case LONG:
                return ColumnValue.fromLong(value.asLong());
            case BOOLEAN:
                return ColumnValue.fromBoolean(value.asBoolean());
            case DOUBLE:
                return ColumnValue.fromDouble(value.asDouble());
            default:
                throw new IllegalArgumentException("不支持的列类型: " + value.getType());
        }
    }

    static TsColumnValue fromSdkColumnValue(ColumnValue value) {
        switch (value.getType()) {
            case STRING:
                return TsColumnValue.of(value.asString());
            case INTEGER:
                return TsColumnValue.of(value.asLong());
            case BOOLEAN:
                return TsColumnValue.of(value.asBoolean());
            case DOUBLE:
                return TsColumnValue.of(value.asDouble());
            default:
                throw new IllegalArgumentException("不支持的 SDK 列类型: " + value.getType());
        }
    }

    static Condition toSdkCondition(RowCondition condition) {
        RowExistenceExpectation expectation;
        switch (condition.getExpectation()) {
            case EXPECT_EXIST:
                expectation = RowExistenceExpectation.EXPECT_EXIST;
                break;
            case EXPECT_NOT_EXIST:
                expectation = RowExistenceExpectation.EXPECT_NOT_EXIST;
                break;
            default:
                expectation = RowExistenceExpectation.IGNORE;
        }
        Condition sdkCondition = new Condition(expectation);
        if (condition.hasColumnCondition()) {
            SingleColumnValueCondition columnCondition = new SingleColumnValueCondition(
                    condition.getExpectColumnName(),
                    SingleColumnValueCondition.CompareOperator.EQUAL,
                    toSdkColumnValue(condition.getExpectColumnValue()));
            // 列缺失视为条件不满足（version CAS 语义）
            columnCondition.setPassIfMissing(false);
            columnCondition.setLatestVersionsOnly(true);
            sdkCondition.setColumnCondition(columnCondition);
        }
        return sdkCondition;
    }

    static TsRow fromSdkRow(Row row) {
        TsPrimaryKey.Builder pkBuilder = TsPrimaryKey.builder();
        for (PrimaryKeyColumn pkColumn : row.getPrimaryKey().getPrimaryKeyColumns()) {
            PrimaryKeyValue value = pkColumn.getValue();
            switch (value.getType()) {
                case STRING:
                    pkBuilder.add(pkColumn.getName(), value.asString());
                    break;
                case INTEGER:
                    pkBuilder.add(pkColumn.getName(), value.asLong());
                    break;
                default:
                    throw new IllegalArgumentException("不支持的主键类型: " + value.getType());
            }
        }
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        for (Column column : row.getColumns()) {
            columns.put(column.getName(), fromSdkColumnValue(column.getValue()));
        }
        return new TsRow(pkBuilder.build(), columns);
    }
}
