package com.example.common.infrastructure.tablestore.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tablestore 数据行（防腐层自有类型，隔离 SDK Row）：主键 + 属性列集合。
 */
public final class TsRow {

    private final TsPrimaryKey primaryKey;

    /** 属性列：列名 → 列值 */
    private final Map<String, TsColumnValue> columns;

    public TsRow(TsPrimaryKey primaryKey, Map<String, TsColumnValue> columns) {
        this.primaryKey = primaryKey;
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    public TsPrimaryKey getPrimaryKey() {
        return primaryKey;
    }

    public Map<String, TsColumnValue> getColumns() {
        return columns;
    }

    public TsColumnValue getColumn(String name) {
        return columns.get(name);
    }

    public boolean hasColumn(String name) {
        return columns.containsKey(name);
    }

    /**
     * 读取 String 列，缺失返回 null。
     *
     * @param name 列名
     * @return 列值或 null
     */
    public String getString(String name) {
        TsColumnValue value = columns.get(name);
        return value == null ? null : value.asString();
    }

    /**
     * 读取 Long 列，缺失返回 null。
     *
     * @param name 列名
     * @return 列值或 null
     */
    public Long getLong(String name) {
        TsColumnValue value = columns.get(name);
        return value == null ? null : value.asLong();
    }

    @Override
    public String toString() {
        return "TsRow{pk=" + primaryKey.encode() + ", columns=" + columns.keySet() + "}";
    }
}
