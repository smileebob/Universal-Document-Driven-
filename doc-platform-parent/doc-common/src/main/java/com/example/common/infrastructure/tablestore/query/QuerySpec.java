package com.example.common.infrastructure.tablestore.query;

import java.util.ArrayList;
import java.util.List;

import com.example.common.infrastructure.tablestore.model.TsColumnValue;

/**
 * 结构化查询规格（防腐层自有 DSL → SearchQuery 的翻译入参，TS-01/TS-05）。
 *
 * <p>约束（design/03 §4.6）：</p>
 * <ul>
 *   <li>单页 {@code 1 ≤ limit ≤ 100}（Tablestore 多元索引硬上限）；</li>
 *   <li>深翻页一律 {@code nextToken} 游标续翻；浅层 {@code offset}（offset+limit ≤ 2000，
 *       由调用方约束）以结构化字段传入，OTS 实现以 token 循环翻页适配
 *       （评审修复 Major-4：不再以伪 token 编码 offset）；</li>
 *   <li>wildcard 词长 ≤ 32、terms 词数 ≤ 1024（在条件构造时校验）；</li>
 *   <li>{@code offset} 与 {@code nextToken} 互斥（二选一分页模式）。</li>
 * </ul>
 */
public final class QuerySpec {

    /** Tablestore 多元索引单页 limit 上限 */
    public static final int MAX_LIMIT = 100;

    private final List<QueryCondition> must;
    private final List<SortField> sorts;
    private final int limit;
    /** 游标（Base64 不透明串；首页为 null） */
    private final String nextToken;
    /** 浅层偏移（评审修复 Major-4；0=不偏移；与 nextToken 互斥） */
    private final long offset;
    private final boolean getTotalCount;

    private QuerySpec(List<QueryCondition> must, List<SortField> sorts,
                      int limit, String nextToken, long offset, boolean getTotalCount) {
        if (offset < 0) {
            throw new IllegalArgumentException("查询 offset 不可为负数: " + offset);
        }
        if (offset > 0 && nextToken != null && !nextToken.isEmpty()) {
            throw new IllegalArgumentException("offset 与 nextToken 不可同时使用（二选一分页模式）");
        }
        this.must = List.copyOf(must);
        this.sorts = List.copyOf(sorts);
        this.limit = limit;
        this.nextToken = nextToken;
        this.offset = offset;
        this.getTotalCount = getTotalCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 以既有规格为蓝本构造新 Builder（条件/排序/总数开关原样复制，
     * 分页参数 limit/nextToken/offset 重置，供 OTS offset 循环翻页逐页构造使用）。
     *
     * @param source 蓝本规格
     * @return 新 Builder
     */
    public static Builder builderFrom(QuerySpec source) {
        Builder builder = new Builder();
        builder.must.addAll(source.must);
        builder.sorts.addAll(source.sorts);
        builder.getTotalCount = source.getTotalCount;
        return builder;
    }

    public List<QueryCondition> getMust() {
        return must;
    }

    public List<SortField> getSorts() {
        return sorts;
    }

    public int getLimit() {
        return limit;
    }

    public String getNextToken() {
        return nextToken;
    }

    /** 浅层偏移（评审修复 Major-4；0=不偏移） */
    public long getOffset() {
        return offset;
    }

    public boolean isGetTotalCount() {
        return getTotalCount;
    }

    /** QuerySpec 构造器 */
    public static final class Builder {

        private final List<QueryCondition> must = new ArrayList<>();
        private final List<SortField> sorts = new ArrayList<>();
        private int limit = 10;
        private String nextToken;
        private long offset;
        private boolean getTotalCount;

        public Builder term(String field, TsColumnValue value) {
            must.add(new QueryCondition.Term(field, value));
            return this;
        }

        public Builder terms(String field, List<TsColumnValue> values) {
            must.add(new QueryCondition.Terms(field, values));
            return this;
        }

        public Builder wildcard(String field, String pattern) {
            must.add(new QueryCondition.Wildcard(field, pattern));
            return this;
        }

        public Builder prefix(String field, String prefix) {
            must.add(new QueryCondition.Prefix(field, prefix));
            return this;
        }

        /**
         * 范围条件。
         *
         * @param field         字段
         * @param from          下界（null=不限）
         * @param fromInclusive 下界是否包含
         * @param to            上界（null=不限）
         * @param toInclusive   上界是否包含
         * @return this
         */
        public Builder range(String field, TsColumnValue from, boolean fromInclusive,
                             TsColumnValue to, boolean toInclusive) {
            must.add(new QueryCondition.Range(field, from, fromInclusive, to, toInclusive));
            return this;
        }

        public Builder exists(String field) {
            must.add(new QueryCondition.Exists(field));
            return this;
        }

        public Builder nestedTerm(String path, String subField, TsColumnValue value) {
            must.add(new QueryCondition.NestedTerm(path, subField, value));
            return this;
        }

        /** Nested 子字段多值属于（RD-09 关联查询 nested_eq 列表语义） */
        public Builder nestedTerms(String path, String subField, List<TsColumnValue> values) {
            must.add(new QueryCondition.NestedTerms(path, subField, values));
            return this;
        }

        public Builder nestedWildcard(String path, String subField, String pattern) {
            must.add(new QueryCondition.NestedWildcard(path, subField, pattern));
            return this;
        }

        public Builder sortAsc(String field) {
            sorts.add(SortField.asc(field));
            return this;
        }

        public Builder sortDesc(String field) {
            sorts.add(SortField.desc(field));
            return this;
        }

        /**
         * 单页条数（1~100）。
         *
         * @param limit 条数
         * @return this
         */
        public Builder limit(int limit) {
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException(
                        "查询 limit 越界（1~" + MAX_LIMIT + "）: " + limit + "，深翻页请使用 nextToken");
            }
            this.limit = limit;
            return this;
        }

        /**
         * 续翻游标（上一页返回的 nextToken）。
         *
         * @param nextToken 游标
         * @return this
         */
        public Builder nextToken(String nextToken) {
            this.nextToken = nextToken;
            return this;
        }

        /**
         * 浅层偏移（评审修复 Major-4：结构化字段替代伪 token）。
         * 与 {@link #nextToken(String)} 互斥；深度约束（offset+limit ≤ 2000）由调用方承担。
         *
         * @param offset 偏移行数（≥0）
         * @return this
         */
        public Builder offset(long offset) {
            this.offset = offset;
            return this;
        }

        public Builder getTotalCount(boolean getTotalCount) {
            this.getTotalCount = getTotalCount;
            return this;
        }

        public QuerySpec build() {
            return new QuerySpec(must, sorts, limit, nextToken, offset, getTotalCount);
        }
    }
}
