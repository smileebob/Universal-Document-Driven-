package com.example.common.infrastructure.tablestore.query;

import java.util.List;

import com.example.common.infrastructure.tablestore.model.TsColumnValue;

/**
 * 结构化查询条件基类（防腐层自有 DSL，TS-01：禁止拼接查询串）。
 *
 * <p>子类覆盖 Tablestore 多元索引常用算子：Term / Terms / Wildcard / Prefix /
 * Range / Exists / Nested(Term) / Nested(Wildcard)。所有条件以 AND（BoolQuery.must）组合。</p>
 */
public abstract class QueryCondition {

    /** 条件类型 */
    public enum Kind {
        /** 等值 */
        TERM,
        /** 多值属于（≤ 1024 词） */
        TERMS,
        /** 通配符（词长 ≤ 32 字符，支持 * 与 ?） */
        WILDCARD,
        /** 前缀 */
        PREFIX,
        /** 范围（开区间/闭区间可配） */
        RANGE,
        /** 列存在 */
        EXISTS,
        /** Nested 子字段等值（attributes.{code} 动态子字段） */
        NESTED_TERM,
        /** Nested 子字段多值属于（RD-09 关联查询：relations[].uuid nested_eq 列表） */
        NESTED_TERMS,
        /** Nested 子字段通配符（attributes.{code} contains，design/09 §4.2） */
        NESTED_WILDCARD
    }

    private final Kind kind;
    private final String field;

    protected QueryCondition(Kind kind, String field) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("查询字段名不可为空");
        }
        this.kind = kind;
        this.field = field;
    }

    public Kind getKind() {
        return kind;
    }

    public String getField() {
        return field;
    }

    /** 等值条件 */
    public static final class Term extends QueryCondition {
        private final TsColumnValue value;

        public Term(String field, TsColumnValue value) {
            super(Kind.TERM, field);
            this.value = value;
        }

        public TsColumnValue getValue() {
            return value;
        }
    }

    /** 多值属于条件 */
    public static final class Terms extends QueryCondition {

        /** Tablestore TermsQuery 词数上限 */
        public static final int MAX_TERMS = 1024;

        private final List<TsColumnValue> values;

        public Terms(String field, List<TsColumnValue> values) {
            super(Kind.TERMS, field);
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("terms 条件值列表不可为空");
            }
            if (values.size() > MAX_TERMS) {
                throw new IllegalArgumentException("terms 条件值数量超过上限 " + MAX_TERMS + "，请拆分查询");
            }
            this.values = List.copyOf(values);
        }

        public List<TsColumnValue> getValues() {
            return values;
        }
    }

    /** 通配符条件（contains/prefix/suffix 语义由调用方在词内加 * 表达） */
    public static final class Wildcard extends QueryCondition {

        /** Tablestore WildcardQuery 词长上限 */
        public static final int MAX_PATTERN_LENGTH = 32;

        private final String pattern;

        public Wildcard(String field, String pattern) {
            super(Kind.WILDCARD, field);
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("wildcard 词不可为空");
            }
            if (pattern.length() > MAX_PATTERN_LENGTH) {
                throw new IllegalArgumentException(
                        "wildcard 词长超过 " + MAX_PATTERN_LENGTH + " 字符上限（Tablestore 硬约束）");
            }
            this.pattern = pattern;
        }

        public String getPattern() {
            return pattern;
        }
    }

    /** 前缀条件 */
    public static final class Prefix extends QueryCondition {
        private final String prefix;

        public Prefix(String field, String prefix) {
            super(Kind.PREFIX, field);
            if (prefix == null || prefix.isEmpty()) {
                throw new IllegalArgumentException("prefix 词不可为空");
            }
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    /** 范围条件（from/to 可空表示单侧开区间） */
    public static final class Range extends QueryCondition {
        private final TsColumnValue from;
        private final boolean fromInclusive;
        private final TsColumnValue to;
        private final boolean toInclusive;

        public Range(String field, TsColumnValue from, boolean fromInclusive,
                     TsColumnValue to, boolean toInclusive) {
            super(Kind.RANGE, field);
            if (from == null && to == null) {
                throw new IllegalArgumentException("range 条件 from/to 不可同时为空");
            }
            this.from = from;
            this.fromInclusive = fromInclusive;
            this.to = to;
            this.toInclusive = toInclusive;
        }

        public TsColumnValue getFrom() {
            return from;
        }

        public boolean isFromInclusive() {
            return fromInclusive;
        }

        public TsColumnValue getTo() {
            return to;
        }

        public boolean isToInclusive() {
            return toInclusive;
        }
    }

    /** 列存在条件 */
    public static final class Exists extends QueryCondition {
        public Exists(String field) {
            super(Kind.EXISTS, field);
        }
    }

    /** Nested 子字段等值条件（path 如 attributes，子字段如 attributes.customer_name 的叶子名） */
    public static final class NestedTerm extends QueryCondition {

        /** Nested 路径（如 attributes / relations） */
        private final String path;
        private final TsColumnValue value;

        /**
         * @param path    Nested 容器字段名（如 attributes）
         * @param subField 子字段名（如 customer_name）
         * @param value    期望值
         */
        public NestedTerm(String path, String subField, TsColumnValue value) {
            super(Kind.NESTED_TERM, path + "." + subField);
            this.path = path;
            this.value = value;
        }

        public String getPath() {
            return path;
        }

        /**
         * 子字段名（不含路径前缀）。
         *
         * @return 叶子字段名
         */
        public String getSubField() {
            return getField().substring(path.length() + 1);
        }

        public TsColumnValue getValue() {
            return value;
        }
    }

    /** Nested 子字段通配符条件（path 如 attributes，contains 语义由调用方加 * 表达） */
    public static final class NestedWildcard extends QueryCondition {

        /** Nested 路径（如 attributes） */
        private final String path;
        private final String pattern;

        /**
         * @param path     Nested 容器字段名（如 attributes）
         * @param subField 子字段名（如 model_name）
         * @param pattern  通配符词（≤ {@value Wildcard#MAX_PATTERN_LENGTH} 字符，支持 * 与 ?）
         */
        public NestedWildcard(String path, String subField, String pattern) {
            super(Kind.NESTED_WILDCARD, path + "." + subField);
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("nested wildcard 词不可为空");
            }
            if (pattern.length() > Wildcard.MAX_PATTERN_LENGTH) {
                throw new IllegalArgumentException("nested wildcard 词长超过 "
                        + Wildcard.MAX_PATTERN_LENGTH + " 字符上限（Tablestore 硬约束）");
            }
            this.path = path;
            this.pattern = pattern;
        }

        public String getPath() {
            return path;
        }

        /**
         * 子字段名（不含路径前缀）。
         *
         * @return 叶子字段名
         */
        public String getSubField() {
            return getField().substring(path.length() + 1);
        }

        public String getPattern() {
            return pattern;
        }
    }

    /** Nested 子字段多值属于条件（RD-09：关联查询经 relations[].uuid nested_eq 列表过滤） */
    public static final class NestedTerms extends QueryCondition {

        /** Nested 路径（如 relations） */
        private final String path;
        private final List<TsColumnValue> values;

        /**
         * @param path     Nested 容器字段名（如 relations）
         * @param subField 子字段名（如 uuid）
         * @param values   期望值列表（1~1024）
         */
        public NestedTerms(String path, String subField, List<TsColumnValue> values) {
            super(Kind.NESTED_TERMS, path + "." + subField);
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("nested terms 条件值列表不可为空");
            }
            if (values.size() > Terms.MAX_TERMS) {
                throw new IllegalArgumentException(
                        "nested terms 条件值数量超过上限 " + Terms.MAX_TERMS + "，请拆分查询");
            }
            this.path = path;
            this.values = List.copyOf(values);
        }

        public String getPath() {
            return path;
        }

        /** 子字段名（不含路径前缀） */
        public String getSubField() {
            return getField().substring(path.length() + 1);
        }

        public List<TsColumnValue> getValues() {
            return values;
        }
    }
}
