package com.example.common.infrastructure.tablestore.query;

/**
 * 排序字段（多元索引 FieldSort 的防腐层映射）。
 */
public final class SortField {

    private final String field;
    private final boolean asc;

    public SortField(String field, boolean asc) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("排序字段名不可为空");
        }
        this.field = field;
        this.asc = asc;
    }

    public static SortField asc(String field) {
        return new SortField(field, true);
    }

    public static SortField desc(String field) {
        return new SortField(field, false);
    }

    public String getField() {
        return field;
    }

    public boolean isAsc() {
        return asc;
    }
}
