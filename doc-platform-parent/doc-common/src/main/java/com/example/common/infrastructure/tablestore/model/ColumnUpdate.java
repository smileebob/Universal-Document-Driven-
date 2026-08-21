package com.example.common.infrastructure.tablestore.model;

/**
 * UpdateRow 列操作：写入列值 或 删除列（防腐层自有类型）。
 */
public final class ColumnUpdate {

    private final String name;

    /** null 表示删除该列 */
    private final TsColumnValue value;

    private ColumnUpdate(String name, TsColumnValue value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("列名不可为空");
        }
        this.name = name;
        this.value = value;
    }

    public static ColumnUpdate put(String name, TsColumnValue value) {
        if (value == null) {
            throw new IllegalArgumentException("put 列值不可为 null，删除列请使用 delete()");
        }
        return new ColumnUpdate(name, value);
    }

    public static ColumnUpdate delete(String name) {
        return new ColumnUpdate(name, null);
    }

    public String getName() {
        return name;
    }

    public boolean isDelete() {
        return value == null;
    }

    public TsColumnValue getValue() {
        return value;
    }

    @Override
    public String toString() {
        return isDelete() ? "DELETE(" + name + ")" : "PUT(" + name + "=" + value + ")";
    }
}
