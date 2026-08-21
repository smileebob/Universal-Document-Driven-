package com.example.common.infrastructure.tablestore.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tablestore 主键（防腐层自有类型，隔离 SDK PrimaryKey）。
 *
 * <p>主键列按定义顺序保持（分区键为第一列）；列值仅支持 String / Long。</p>
 */
public final class TsPrimaryKey {

    /** 主键列：列名 → 值（String 或 Long），保持插入顺序 */
    private final Map<String, Object> columns;

    private TsPrimaryKey(Map<String, Object> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("主键列不可为空");
        }
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 单列主键快捷构造。
     *
     * @param name  列名
     * @param value 列值（String 或 Long）
     * @return 主键
     */
    public static TsPrimaryKey of(String name, Object value) {
        return builder().add(name, value).build();
    }

    public Map<String, Object> getColumns() {
        return columns;
    }

    /**
     * 分区键值（第一列，Tablestore 约定）。
     *
     * @return 分区键值
     */
    public Object partitionValue() {
        return columns.values().iterator().next();
    }

    /**
     * 分区键字符串值。
     *
     * @return 分区键值字符串
     */
    public String partitionValueAsString() {
        return String.valueOf(partitionValue());
    }

    public Object get(String name) {
        return columns.get(name);
    }

    /**
     * 内存存储/日志用编码：{@code col1=val1|col2=val2}。
     *
     * @return 编码串
     */
    public String encode() {
        return columns.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("|"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TsPrimaryKey)) {
            return false;
        }
        return columns.equals(((TsPrimaryKey) o).columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }

    @Override
    public String toString() {
        return encode();
    }

    /** 主键构造器 */
    public static final class Builder {

        private final Map<String, Object> columns = new LinkedHashMap<>();

        /**
         * 追加主键列。
         *
         * @param name  列名
         * @param value String 或 Long 值
         * @return this
         */
        public Builder add(String name, Object value) {
            Objects.requireNonNull(name, "主键列名不可为 null");
            if (!(value instanceof String) && !(value instanceof Long)) {
                throw new IllegalArgumentException("主键列值仅支持 String/Long: " + name);
            }
            if (columns.containsKey(name)) {
                throw new IllegalArgumentException("主键列重复: " + name);
            }
            columns.put(name, value);
            return this;
        }

        public TsPrimaryKey build() {
            return new TsPrimaryKey(columns);
        }
    }
}
