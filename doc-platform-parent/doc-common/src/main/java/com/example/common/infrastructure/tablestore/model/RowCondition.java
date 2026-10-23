package com.example.common.infrastructure.tablestore.model;

import java.util.List;
import java.util.Map;

/**
 * 行级条件（防腐层自有类型，隔离 SDK Condition/SingleColumnValueCondition）。
 *
 * <p>承载三类 Tablestore 条件更新语义（TS-01）：</p>
 * <ul>
 *   <li>{@code IGNORE}：无条件写；</li>
 *   <li>{@code EXPECT_EXIST} / {@code EXPECT_NOT_EXIST}：行存在条件（唯一行 CAS 基础）；</li>
 *   <li>可选列等值条件（如 {@code version == 读取值}、{@code current_status == 期望旧状态}），
 *       乐观锁与防重复流转的统一入口；多列条件按 AND 组合。</li>
 * </ul>
 */
public final class RowCondition {

    /** 行存在期望 */
    public enum Expectation {
        /** 无条件 */
        IGNORE,
        /** 行必须存在 */
        EXPECT_EXIST,
        /** 行必须不存在（幂等建行） */
        EXPECT_NOT_EXIST
    }

    private static final RowCondition IGNORE = new RowCondition(Expectation.IGNORE, List.of());
    private static final RowCondition EXPECT_EXIST = new RowCondition(Expectation.EXPECT_EXIST, List.of());
    private static final RowCondition EXPECT_NOT_EXIST =
            new RowCondition(Expectation.EXPECT_NOT_EXIST, List.of());

    private final Expectation expectation;

    /** 列等值条件（AND 组合；空表示无列条件） */
    private final List<ColumnCondition> columnConditions;

    private RowCondition(Expectation expectation, List<ColumnCondition> columnConditions) {
        this.expectation = expectation;
        this.columnConditions = columnConditions == null ? List.of() : List.copyOf(columnConditions);
    }

    /** 单列等值条件。 */
    public record ColumnCondition(String columnName, TsColumnValue value) {
    }

    public static RowCondition ignore() {
        return IGNORE;
    }

    public static RowCondition expectExist() {
        return EXPECT_EXIST;
    }

    public static RowCondition expectNotExist() {
        return EXPECT_NOT_EXIST;
    }

    /**
     * 行存在 + 列等值条件（乐观锁 / 状态 CAS）。
     *
     * @param columnName 列名（如 version / current_status）
     * @param value      期望值
     * @return 条件对象
     */
    public static RowCondition expectExistAndColumnEq(String columnName, TsColumnValue value) {
        return new RowCondition(Expectation.EXPECT_EXIST,
                List.of(new ColumnCondition(columnName, value)));
    }

    /**
     * 行存在 + 多列等值条件（AND）。用于日志投影认领、顺序游标等需要
     * “状态 + 租约持有者”同时匹配的 CAS。
     *
     * @param conditions 列名 → 期望值（保持插入序）
     * @return 条件对象
     */
    public static RowCondition expectExistAndAllEq(Map<String, TsColumnValue> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return EXPECT_EXIST;
        }
        List<ColumnCondition> list = conditions.entrySet().stream()
                .map(entry -> new ColumnCondition(entry.getKey(), entry.getValue()))
                .toList();
        return new RowCondition(Expectation.EXPECT_EXIST, list);
    }

    public Expectation getExpectation() {
        return expectation;
    }

    public boolean hasColumnCondition() {
        return !columnConditions.isEmpty();
    }

    /** 列条件列表（AND；空表示无列条件）。 */
    public List<ColumnCondition> getColumnConditions() {
        return columnConditions;
    }

    /** 兼容单列调用方：首列条件名。 */
    public String getExpectColumnName() {
        return columnConditions.isEmpty() ? null : columnConditions.get(0).columnName();
    }

    /** 兼容单列调用方：首列条件值。 */
    public TsColumnValue getExpectColumnValue() {
        return columnConditions.isEmpty() ? null : columnConditions.get(0).value();
    }

    @Override
    public String toString() {
        return "RowCondition{" + expectation
                + (columnConditions.isEmpty() ? "" : ", " + columnConditions) + "}";
    }
}
