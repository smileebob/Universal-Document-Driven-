package com.example.common.infrastructure.tablestore.model;

import java.util.Objects;

/**
 * Tablestore 属性列值（防腐层自有类型，隔离 SDK ColumnValue）。
 *
 * <p>仅支持 Tablestore 标量类型：STRING / LONG / BOOLEAN / DOUBLE。
 * JSON 结构（attributes/relations 等）一律序列化为 STRING 列存储（design/03 §二）。</p>
 */
public final class TsColumnValue {

    /** 列值类型 */
    public enum Type {
        /** 字符串（含 JSON 序列化列） */
        STRING,
        /** 64 位整型（时间戳 UTC 毫秒、version、状态值等） */
        LONG,
        /** 布尔 */
        BOOLEAN,
        /** 双精度浮点 */
        DOUBLE
    }

    private final Type type;
    private final Object value;

    private TsColumnValue(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static TsColumnValue of(String value) {
        Objects.requireNonNull(value, "列值不可为 null（删除列请使用 ColumnUpdate.delete）");
        return new TsColumnValue(Type.STRING, value);
    }

    public static TsColumnValue of(long value) {
        return new TsColumnValue(Type.LONG, value);
    }

    public static TsColumnValue of(boolean value) {
        return new TsColumnValue(Type.BOOLEAN, value);
    }

    public static TsColumnValue of(double value) {
        return new TsColumnValue(Type.DOUBLE, value);
    }

    public Type getType() {
        return type;
    }

    /**
     * 原始装箱值（String/Long/Boolean/Double）。
     *
     * @return 值对象
     */
    public Object getObject() {
        return value;
    }

    public String asString() {
        if (type != Type.STRING) {
            throw new IllegalStateException("列值类型不是 STRING: " + type);
        }
        return (String) value;
    }

    public long asLong() {
        if (type != Type.LONG) {
            throw new IllegalStateException("列值类型不是 LONG: " + type);
        }
        return (Long) value;
    }

    public boolean asBoolean() {
        if (type != Type.BOOLEAN) {
            throw new IllegalStateException("列值类型不是 BOOLEAN: " + type);
        }
        return (Boolean) value;
    }

    public double asDouble() {
        if (type != Type.DOUBLE) {
            throw new IllegalStateException("列值类型不是 DOUBLE: " + type);
        }
        return (Double) value;
    }

    /**
     * 数值化比较值：LONG/DOUBLE 按 double，BOOLEAN 按 0/1，STRING 返回 null（字典序另行处理）。
     *
     * @return 数值或 null
     */
    public Double asNumberOrNull() {
        switch (type) {
            case LONG:
                return (double) (Long) value;
            case DOUBLE:
                return (Double) value;
            case BOOLEAN:
                return ((Boolean) value) ? 1.0 : 0.0;
            default:
                return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TsColumnValue)) {
            return false;
        }
        TsColumnValue that = (TsColumnValue) o;
        return type == that.type && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return type + "(" + value + ")";
    }
}
