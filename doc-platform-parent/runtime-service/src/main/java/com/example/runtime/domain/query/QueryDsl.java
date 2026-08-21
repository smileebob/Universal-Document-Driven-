package com.example.runtime.domain.query;

import java.util.List;
import java.util.Map;

/**
 * 统一查询 DSL 领域模型（POST /api/v1/query，PRD 05 RD-10 / design/09 §四）。
 *
 * <p>controller 层 Request DTO 映射为本模型后交 QueryService 翻译 QuerySpec；
 * 报文基线 PRD 11 §3.4：响应 {@code {total, items, next_token, aggregations}}。</p>
 */
public final class QueryDsl {

    /** 查询目标：实体数据 */
    public static final String TARGET_ENTITY_DATA = "entity_data";

    /** 查询目标：单据数据 */
    public static final String TARGET_DOC_DATA = "doc_data";

    private QueryDsl() {
    }

    /**
     * 查询请求（DSL 全量）。
     *
     * @param target       查询目标：entity_data / doc_data（其余取值 40002）
     * @param filters      过滤条件（≤32，L5）
     * @param sorts        排序（默认 create_time desc）
     * @param page         分页（offset 模式或 token 模式）
     * @param aggregations 聚合（≤5，L6）
     */
    public record QueryRequest(String target, List<FilterCondition> filters,
                               List<SortCondition> sorts, PageSpec page,
                               List<AggregationDef> aggregations) {
    }

    /**
     * 过滤条件。
     *
     * @param field 字段（顶层系统列 / attributes.{code} / relations[].code|uuid）
     * @param op    操作符（PRD 12 §1.8：eq/in/gte/lte/gt/lt/contains/prefix/exists/nested_eq）
     * @param value 值（in 为数组；exists 忽略）
     */
    public record FilterCondition(String field, String op, Object value) {
    }

    /**
     * 排序条件。
     *
     * @param field 索引列字段
     * @param order asc / desc
     */
    public record SortCondition(String field, String order) {
    }

    /**
     * 分页参数（offset 模式与 token 模式二选一，design/09 §4.4 L4）。
     *
     * @param offset 偏移（token 模式为 null）
     * @param limit  单页条数（1~100，L1）
     * @param token  续翻游标（offset 模式为 null）
     */
    public record PageSpec(Long offset, Integer limit, String token) {
    }

    /**
     * 聚合定义。
     *
     * @param name  聚合名（响应 key，不可重复）
     * @param type  count / sum / avg / group_by
     * @param field 聚合字段（count 可空）
     */
    public record AggregationDef(String name, String type, String field) {
    }

    /**
     * 查询结果。
     *
     * @param total        命中总数
     * @param items        本页条目（行投影 Map）
     * @param nextToken    续翻游标（仅 token 模式返回；null=末页/offset 模式）
     * @param aggregations 聚合结果：name → 桶列表（group_by [{key,count}]；count [{count}]；sum/avg [{value}]）；
     *                     扫描达上限时附 {@code "truncated": true} 标识（评审修复 Major-8，
     *                     截断时聚合为近似值，PRD 11 §3.4）
     */
    public record QueryResult(long total, List<Map<String, Object>> items,
                              String nextToken, Map<String, Object> aggregations) {
    }
}
