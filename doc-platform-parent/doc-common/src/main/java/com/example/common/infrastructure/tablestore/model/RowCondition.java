package com.example.common.infrastructure.tablestore.model;

/**
 * 行级条件（防腐层自有类型，隔离 SDK Condition/SingleColumnValueCondition）。
 *
 * <p>承载三类 Tablestore 条件更新语义（TS-01）：</p>
 * <ul>
 *   <li>{@code IGNORE}：无条件写；</li>
 *   <li>{@code EXPECT_EXIST} / {@code EXPECT_NOT_EXIST}：行存在条件（唯一行 CAS 基础）；</li>
 *   <li>可选列等值条件（如 {@code version == 读取值}、{@code current_status == 期望旧状态}），
 *       乐观锁与防重复流转的统一入口。</li>
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

    private static final RowCondition IGNORE = new RowCondition(Expectation.IGNORE, null, null);
    private static final RowCondition EXPECT_EXIST = new RowCondition(Expectation.EXPECT_EXIST, null, null);
    private static final RowCondition EXPECT_NOT_EXIST =
            new RowCondition(Expectation.EXPECT_NOT_EXIST, null, null);

    private final Expectation expectation;

    /** 列条件列名（null 表示无列条件） */
    private final String expectColumnName;

    /** 列条件期望值（等值比较） */
    private final TsColumnValue expectColumnValue;

    private RowCondition(Expectation expectation, String expectColumnName, TsColumnValue expectColumnValue) {
        this.expectation = expectation;
        this.expectColumnName = expectColumnName;
        this.expectColumnValue = expectColumnValue;
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
        return new RowCondition(Expectation.EXPECT_EXIST, columnName, value);
    }

    public Expectation getExpectation() {
        return expectation;
    }

    public boolean hasColumnCondition() {
        return expectColumnName != null;
    }

    public String getExpectColumnName() {
        return expectColumnName;
    }

    public TsColumnValue getExpectColumnValue() {
        return expectColumnValue;
    }

    @Override
    public String toString() {
        return "RowCondition{" + expectation
                + (expectColumnName == null ? "" : ", " + expectColumnName + "==" + expectColumnValue) + "}";
    }
}
