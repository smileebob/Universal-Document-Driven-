package com.example.runtime.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.runtime.domain.query.QueryDsl;
import com.example.runtime.domain.query.QueryDsl.AggregationDef;
import com.example.runtime.domain.query.QueryDsl.FilterCondition;
import com.example.runtime.domain.query.QueryDsl.QueryRequest;
import com.example.runtime.domain.query.QueryDsl.QueryResult;
import com.example.runtime.domain.query.QueryDsl.SortCondition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一查询服务（RD-10，POST /api/v1/query，design/09 §四）。
 *
 * <p>DSL → doc-common {@link QuerySpec} 结构化翻译（4.2 对照表），
 * 降级矩阵 L1~L7 全量落实（4.4）：</p>
 * <ul>
 *   <li>L1 limit ≤ 100 强制拒绝；L2 in 词数 ≤ 1024；L3 contains ≤ 30 字符（wildcard 模式 32 字符含两侧 *）；</li>
 *   <li>L4 offset+limit ≤ 2000，超限拒绝并引导 next_token；offset 模式响应不回 next_token；</li>
 *   <li>L5 filters ≤ 32；L6 aggregations ≤ 5；L7 group_by 截断 Top 100 桶。</li>
 * </ul>
 *
 * <p>强制过滤：按 target 自动注入 {@code status != 0}（逻辑删不可见，4.3）。
 * QuerySpec 无聚合算子：sum/avg/group_by 以续翻取行（≤ {@value #AGGREGATION_SCAN_LIMIT} 行）
 * 内存计算实现（实现层差异 B4，语义对齐 PRD 11 §3.4 响应结构）。</p>
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    /** L5：filters 条数上限 */
    private static final int MAX_FILTERS = 32;

    /** L6：aggregations 条数上限 */
    private static final int MAX_AGGREGATIONS = 5;

    /** L2：in 词数上限 */
    private static final int MAX_IN_TERMS = 1024;

    /** L3：contains 值长度上限（wildcard 模式 32 字符硬约束扣除两侧 *） */
    private static final int MAX_CONTAINS_LENGTH = 30;

    /** L4：offset 模式深度上限（offset + limit） */
    private static final long MAX_OFFSET_WINDOW = 2000L;

    /** 聚合内存计算最大取行数 */
    private static final int AGGREGATION_SCAN_LIMIT = 1000;

    /** L7：group_by Top N 桶 */
    private static final int GROUP_BY_TOP_N = 100;

    /** attributes 动态属性字段前缀 */
    private static final String ATTRIBUTES_PREFIX = "attributes.";

    /** RD-09：ref 关联查询字段分隔符（{attribute_code}.ref.{display_field}） */
    private static final String REF_SEPARATOR = ".ref.";

    /** RD-09：主表候选扫描行数上限（防大表全扫） */
    private static final int REF_SCAN_LIMIT = 1000;

    /** RD-09：目标实体主表（batchGet 反查展示字段） */
    private static final String ENTITY_TABLE = "entity_data";

    /** relations 嵌套数组字段（nested_eq 专用） */
    private static final String RELATIONS_CODE = "relations[].code";
    private static final String RELATIONS_UUID = "relations[].uuid";

    /** ref 关联查询前置解析结果（匹配 uuid 集 + uuid→展示载荷） */
    private record RefResolution(String attrCode, Map<String, Map<String, Object>> displays) {
    }

    /** 查询目标（表 / 索引 / 状态列 / 字段白名单） */
    private record Target(String table, String index, String statusField, String deletedField,
                          Set<String> topFields, Set<String> numericFields) {
    }

    private static final Target ENTITY_TARGET = new Target("entity_data", "entity_data_index",
            "status", null,
            Set.of("uuid", "entity_code", "entity_type", "status", "creator", "create_dept",
                    "create_time", "update_time"),
            Set.of("status", "create_time", "update_time"));

    private static final Target DOC_TARGET = new Target("doc_data", "doc_data_index",
            "current_status", "deleted",
            Set.of("uuid", "doc_code", "doc_type", "current_status", "creator", "create_dept",
                    "create_time", "update_time"),
            Set.of("doc_type", "current_status", "create_time", "update_time"));

    /** 分页解析结果（token 模式与 offset 模式二选一） */
    private record Page(boolean tokenMode, long offset, int limit, String token) {
    }

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public QueryService(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行统一查询。
     *
     * @param request DSL 请求（target/filters/sorts/page/aggregations）
     * @return 查询结果（total/items/next_token/aggregations）
     * @throws BizException 40002 非法 DSL（降级矩阵各条）
     */
    public QueryResult execute(QueryRequest request) {
        if (request == null) {
            throw invalid("查询请求体不可为空");
        }
        Target target = resolveTarget(request.target());
        validateAggregations(request.aggregations());
        Page page = resolvePage(request.page());

        // 评审项 4：filters 条数校验前置——resolveRefFilters 含最多 1000 行候选扫描
        // + batchGet，不得在超限请求上白白消耗资源（原校验在 applyFilters 才执行）
        validateFilterCount(request.filters());

        // RD-09 ref 过滤前置解析：零命中直接返回空结果页（total=0，不构造查询），
        // 不再以恒假条件注入（uuid 为主键非索引字段，真实 OTS 拒绝 field_name[uuid]）
        List<RefResolution> refResolutions = resolveRefFilters(target, request.filters());
        if (refResolutions.stream().anyMatch(resolution -> resolution.displays().isEmpty())) {
            return new QueryResult(0L, List.of(), null, Map.of());
        }

        QuerySpec.Builder builder = QuerySpec.builder();
        for (RefResolution resolution : refResolutions) {
            applyRefMainConditions(builder, resolution);
        }
        applyFilters(builder, target, request.filters());
        injectActiveStatusFilter(builder, target);
        applySorts(builder, target, request.sorts());
        builder.limit(page.limit()).getTotalCount(true);
        if (page.tokenMode()) {
            if (page.token() != null) { builder.nextToken(page.token()); }
        } else if (page.offset() > 0) {
            // 结构化 offset（评审修复 Major-4）：OTS 实现以 token 循环翻页适配，
            // 仿真层直接叠加 offset；不再以伪 token 编码偏移
            builder.offset(page.offset());
        }

        SearchResult result = template.search(target.table(), target.index(), builder.build());
        long total = result.getTotalCount();
        List<Map<String, Object>> items = new ArrayList<>(result.getRows().size());
        for (TsRow row : result.getRows()) {
            items.add(projectRow(row));
        }
        if (!refResolutions.isEmpty()) {
            // 评审项 3：跨 Nested 元素误命中后置过滤——uuid terms 与 attribute_code term
            // 被翻译成两个独立 NestedQuery，AND 组合不要求同一元素同时满足，可能返回
            // 超集幽灵行；仅保留 relations 中存在同一元素同时满足 attribute_code==attrCode
            // 且 uuid∈命中集的条目，并相应修正 total
            total -= removeRefGhostRows(items, refResolutions);
        }
        // offset 模式响应不回 next_token（L4：深翻一律 token 模式）
        String nextToken = page.tokenMode() ? result.getNextToken() : null;
        Map<String, Object> aggregations =
                computeAggregations(target, request.filters(), request.aggregations(), total);
        if (!refResolutions.isEmpty()) {
            attachRefDisplay(items, refResolutions);
        }
        return new QueryResult(total, items, nextToken, aggregations);
    }

    // ==================== 校验 ====================

    /** target 鉴权（4.3：entity_data / doc_data，其余 40002） */
    private Target resolveTarget(String target) {
        if (QueryDsl.TARGET_ENTITY_DATA.equals(target)) {
            return ENTITY_TARGET;
        }
        if (QueryDsl.TARGET_DOC_DATA.equals(target)) {
            return DOC_TARGET;
        }
        throw invalid("「target」仅支持 entity_data / doc_data");
    }

    /** L6：聚合条数 / 命名 / 类型校验 */
    private void validateAggregations(List<AggregationDef> aggregations) {
        if (aggregations == null || aggregations.isEmpty()) {
            return;
        }
        if (aggregations.size() > MAX_AGGREGATIONS) {
            throw invalid("聚合条件数量超出限制(" + MAX_AGGREGATIONS + ")");
        }
        Set<String> names = new HashSet<>();
        for (AggregationDef def : aggregations) {
            if (def == null || def.name() == null || def.name().isBlank()) {
                throw invalid("聚合 name 不可为空");
            }
            if (!names.add(def.name())) {
                throw invalid("聚合 name 重复: " + def.name());
            }
            String type = def.type() == null ? "" : def.type().toLowerCase(Locale.ROOT);
            if (!type.equals("count") && !type.equals("sum") && !type.equals("avg")
                    && !type.equals("group_by")) {
                throw invalid("不支持的聚合类型: " + def.type());
            }
            if (!type.equals("count") && (def.field() == null || def.field().isBlank())) {
                throw invalid("聚合 " + def.name() + " 缺少 field");
            }
        }
    }

    /** L1/L4：分页解析（limit 1~100；offset 模式 offset+limit ≤ 2000） */
    private Page resolvePage(QueryDsl.PageSpec page) {
        Integer limitRaw = page == null ? null : page.limit();
        int limit = limitRaw == null ? 10 : limitRaw;
        if (limit < 1 || limit > QuerySpec.MAX_LIMIT) {
            throw invalid("查询 limit 越界（1~" + QuerySpec.MAX_LIMIT + "），深翻页请使用 next_token");
        }
        String token = page == null ? null : page.token();
        Long offsetRaw = page == null ? null : page.offset();
        boolean hasToken = token != null && !token.isBlank();
        boolean hasOffset = offsetRaw != null;
        if (hasToken && hasOffset) {
            throw invalid("offset 与 token 不可同时使用（二选一分页模式）");
        }
        if (hasToken) {
            return new Page(true, 0L, limit, token);
        }
        if (offsetRaw == null) { return new Page(true, 0L, limit, null); } long offset = offsetRaw;
        if (offset < 0) {
            throw invalid("offset 不可为负数");
        }
        if (offset + limit > MAX_OFFSET_WINDOW) {
            throw invalid("offset 分页深度超限（offset+limit≤" + MAX_OFFSET_WINDOW
                    + "），请改用 next_token 续翻");
        }
        return new Page(false, offset, limit, null);
    }

    /** L5：filters 条数前置校验（评审项 4：不得在超限请求上执行 ref 候选扫描） */
    private void validateFilterCount(List<FilterCondition> filters) {
        if (filters != null && filters.size() > MAX_FILTERS) {
            throw invalid("过滤条件数量超出限制(" + MAX_FILTERS + ")");
        }
    }

    /** L5 + 逐条件翻译（4.2 对照表） */
    private void applyFilters(QuerySpec.Builder builder, Target target, List<FilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        validateFilterCount(filters);
        for (FilterCondition filter : filters) {
            applyFilter(builder, target, filter);
        }
    }

    /** ref 关联查询字段判定（attributes. 前缀可省略，含 .ref. 分隔符） */
    private static boolean isRefDisplayField(String field) {
        String body = field.startsWith(ATTRIBUTES_PREFIX)
                ? field.substring(ATTRIBUTES_PREFIX.length()) : field;
        return body.contains(REF_SEPARATOR);
    }

    private void applyFilter(QuerySpec.Builder builder, Target target, FilterCondition filter) {
        if (filter == null || filter.field() == null || filter.field().isBlank()
                || filter.op() == null || filter.op().isBlank()) {
            throw invalid("过滤条件 field/op 不可为空");
        }
        String field = filter.field();
        String op = filter.op().toLowerCase(Locale.ROOT);
        // RD-09：{attribute_code}.ref.{display_field} 已在 execute() 前置解析（短路/主条件）
        if (isRefDisplayField(field)) {
            return;
        }
        // attributes.{code}：支持 eq（nestedTerm 子字段等值）与 contains（nestedWildcard，design/09 §4.2）
        if (field.startsWith(ATTRIBUTES_PREFIX)) {
            String subField = field.substring(ATTRIBUTES_PREFIX.length());
            if ("eq".equals(op)) {
                builder.nestedTerm("attributes", subField, toColumn(field, filter.value()));
                return;
            }
            if ("contains".equals(op)) {
                String value = requireStringValue(field, filter.value());
                if (value.length() > MAX_CONTAINS_LENGTH) {
                    throw invalid("contains 条件值超出 " + MAX_CONTAINS_LENGTH + " 字符限制");
                }
                builder.nestedWildcard("attributes", subField, "*" + value + "*");
                return;
            }
            throw invalid(field + " 暂仅支持 eq / contains 操作符");
        }
        // relations[].code / relations[].uuid：关联反查（nested_eq 单值 / nested_in 多值，RD-09/11）
        if (RELATIONS_CODE.equals(field) || RELATIONS_UUID.equals(field)) {
            String subField = RELATIONS_CODE.equals(field) ? "code" : "uuid";
            if ("nested_eq".equals(op)) {
                builder.nestedTerm("relations", subField, toColumn(field, filter.value()));
                return;
            }
            if ("nested_in".equals(op)) {
                if (!(filter.value() instanceof List<?> list) || list.isEmpty()) {
                    throw invalid(field + " nested_in 条件值须为非空数组");
                }
                if (list.size() > MAX_IN_TERMS) {
                    throw invalid(field + " nested_in 条件值数量超出限制(" + MAX_IN_TERMS + ")");
                }
                List<TsColumnValue> terms = new ArrayList<>(list.size());
                for (Object item : list) {
                    terms.add(toColumn(field, item));
                }
                builder.nestedTerms("relations", subField, terms);
                return;
            }
            throw invalid(field + " 仅支持 nested_eq / nested_in 操作符");
        }
        if (!target.topFields().contains(field)) {
            throw invalid("不支持的查询字段: " + field);
        }
        switch (op) {
            case "eq" -> builder.term(field, toColumnTyped(target, field, filter.value()));
            case "in" -> applyIn(builder, target, field, filter.value());
            case "gte" -> applyRange(builder, target, field, filter.value(), true, true);
            case "gt" -> applyRange(builder, target, field, filter.value(), false, true);
            case "lte" -> applyRange(builder, target, field, filter.value(), true, false);
            case "lt" -> applyRange(builder, target, field, filter.value(), false, false);
            case "contains" -> {
                requireStringField(target, field, op);
                String value = requireStringValue(field, filter.value());
                if (value.length() > MAX_CONTAINS_LENGTH) {
                    throw invalid("contains 条件值超出 " + MAX_CONTAINS_LENGTH + " 字符限制");
                }
                builder.wildcard(field, "*" + value + "*");
            }
            case "prefix" -> {
                requireStringField(target, field, op);
                builder.prefix(field, requireStringValue(field, filter.value()));
            }
            case "exists" -> builder.exists(field);
            default -> throw invalid("不支持的操作符: " + filter.op());
        }
    }

    /** 范围条件翻译：isFrom=true 为下界（gte/gt），否则上界（lte/lt） */
    private void applyRange(QuerySpec.Builder builder, Target target, String field, Object value,
                            boolean inclusive, boolean isFrom) {
        TsColumnValue bound = toColumnTyped(target, field, value);
        if (isFrom) {
            builder.range(field, bound, inclusive, null, false);
        } else {
            builder.range(field, null, false, bound, inclusive);
        }
    }

    private void applyIn(QuerySpec.Builder builder, Target target, String field, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw invalid("in 条件值须为非空数组: " + field);
        }
        if (list.size() > MAX_IN_TERMS) {
            throw invalid("in 条件值数量超出限制(" + MAX_IN_TERMS + ")");
        }
        List<TsColumnValue> values = new ArrayList<>(list.size());
        for (Object item : list) {
            values.add(toColumnTyped(target, field, item));
        }
        builder.terms(field, values);
    }

    /**
     * 强制过滤：逻辑删不可见（design/09 §4.3，status != 0 语义）。
     *
     * <p>doc_data 逻辑删标记为 deleted 列（0=有效/1=已删，RD-08），current_status
     * 是业务状态（100/10/20/30/99 任意值），不可参与活跃过滤——否则单据流转后
     * 即从列表消失；实体行 status 仅 1/0 两态，QuerySpec 无 must_not 算子，
     * 以 terms(status,[1,100]) 近似 status != 0。</p>
     */
    private void injectActiveStatusFilter(QuerySpec.Builder builder, Target target) {
        if (target.deletedField() != null) {
            builder.term(target.deletedField(), TsColumnValue.of(0L));
            return;
        }
        builder.terms(target.statusField(), List.of(TsColumnValue.of(1L), TsColumnValue.of(100L)));
    }

    /** 排序翻译（仅顶层列；缺省 create_time desc） */
    private void applySorts(QuerySpec.Builder builder, Target target, List<SortCondition> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            builder.sortDesc("create_time");
            return;
        }
        for (SortCondition sort : sorts) {
            if (sort == null || sort.field() == null || !target.topFields().contains(sort.field())) {
                throw invalid("不支持的排序字段: " + (sort == null ? null : sort.field()));
            }
            String order = sort.order() == null ? "desc" : sort.order().toLowerCase(Locale.ROOT);
            if ("asc".equals(order)) {
                builder.sortAsc(sort.field());
            } else if ("desc".equals(order)) {
                builder.sortDesc(sort.field());
            } else {
                throw invalid("排序 order 仅支持 asc / desc: " + sort.order());
            }
        }
    }

    // ==================== RD-09 ref 关联查询 ====================

    /**
     * ref 关联查询前置解析（leader 补项 RD-09）：条件字段 {attribute_code}.ref.{display_field}。
     *
     * <p>链路（禁多元索引 join / 二级反查表）：</p>
     * <ol>
     *   <li>主表 relations[].attribute_code 定位候选单据，提取引用目标 uuid 列表；</li>
     *   <li>batchGet 目标实体主行，按 display_field 值 eq/contains 匹配出命中 uuid 集；</li>
     *   <li>命中集经 relations[].uuid nested_eq（列表）过滤主查询。</li>
     * </ol>
     * <p><b>零命中短路（代码级）</b>：候选空或匹配空时返回空 displays，execute()
     * 直接返回空结果页（total=0，不构造任何查询）——uuid 是主键而非 doc_data_index
     * 索引字段，此前「term(uuid,\"\") 恒假条件」注入会被真实 OTS 拒绝
     * （OTSParameterInvalid field_name[uuid] is not existed，trace 5750bb85）。</p>
     */
    private List<RefResolution> resolveRefFilters(Target target, List<FilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<RefResolution> resolutions = new ArrayList<>();
        for (FilterCondition filter : filters) {
            if (filter == null || filter.field() == null || filter.op() == null
                    || !isRefDisplayField(filter.field())) {
                continue;
            }
            resolutions.add(resolveRefFilter(target, filter));
        }
        return resolutions;
    }

    /** 单条 ref 过滤解析：校验 + 候选扫描 + 实体回读匹配（详见 {@link #resolveRefFilters}） */
    private RefResolution resolveRefFilter(Target target, FilterCondition filter) {
        if (target != DOC_TARGET) {
            throw invalid("ref 关联查询仅支持 target=doc_data");
        }
        String refBody = filter.field().startsWith(ATTRIBUTES_PREFIX)
                ? filter.field().substring(ATTRIBUTES_PREFIX.length()) : filter.field();
        int idx = refBody.indexOf(REF_SEPARATOR);
        String attrCode = refBody.substring(0, idx);
        String displayField = refBody.substring(idx + REF_SEPARATOR.length());
        if (attrCode.isBlank() || displayField.isBlank()) {
            throw invalid("ref 关联字段格式非法: " + refBody);
        }
        String op = filter.op().toLowerCase(Locale.ROOT);
        if (!"eq".equals(op) && !"contains".equals(op)) {
            throw invalid(refBody + " 暂仅支持 eq / contains 操作符");
        }
        String value = requireStringValue(refBody, filter.value());
        if ("contains".equals(op) && value.length() > MAX_CONTAINS_LENGTH) {
            // WildcardQuery 词长硬约束兜底（含两侧 * 共 32 字符）→ 400 + 40002
            throw invalid("contains 条件值超出 " + MAX_CONTAINS_LENGTH + " 字符限制");
        }

        // ① 主表扫描提取候选目标 uuid（relations[].attribute_code = attrCode）
        Map<String, java.util.LinkedHashSet<String>> candidates = scanRefCandidates(target, attrCode);
        if (candidates.isEmpty()) {
            log.info("[query][ref] 候选为空，短路返回空结果页: attrCode={}", attrCode);
            return new RefResolution(attrCode, new LinkedHashMap<>());
        }
        // ② batchGet 目标实体主行，按 display_field 匹配（无 join、无二级反查表）
        List<TsPrimaryKey> pks = new ArrayList<>();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> entry : candidates.entrySet()) {
            for (String targetUuid : entry.getValue()) {
                pks.add(TsPrimaryKey.builder()
                        .add("partition_key", PartitionKeys.dataKey(entry.getKey(), targetUuid))
                        .add("uuid", targetUuid)
                        .build());
            }
        }
        Map<String, Map<String, Object>> displays = new LinkedHashMap<>();
        List<TsRow> fetched = template.batchGet(ENTITY_TABLE, pks);
        for (TsRow row : fetched) {
            if (row == null || row.getColumns().isEmpty()) {
                continue;
            }
            if (!isTargetActive(row) || !displayMatches(row, displayField, op, value)) {
                continue;
            }
            String targetUuid = String.valueOf(row.getPrimaryKey().get("uuid"));
            Map<String, Object> display = new LinkedHashMap<>();
            display.put(displayField, displayValue(row, displayField));
            displays.put(targetUuid, display);
        }
        if (displays.isEmpty()) {
            // 候选存在但实体回读零匹配：逐环节留痕便于真实环境定位（数据条件 or 回读缺失）
            log.warn("[query][ref] 候选存在但实体匹配为空，短路返回空结果页: attrCode={}, "
                            + "候选目标数={}, 回读行数={}, displayField={}, op={}",
                    attrCode, pks.size(), fetched.size(), displayField, op);
        }
        return new RefResolution(attrCode, displays);
    }

    /**
     * ref 主查询条件注入：relations[].uuid nested_eq（命中集）+ attribute_code 收敛（RD-09 链路③）。
     *
     * <p>评审项 3：两条件被翻译成两个独立 NestedQuery，AND 不要求同一嵌套元素同时满足
     * （存在跨元素误命中超集），取回后由 {@link #removeRefGhostRows} 后置过滤兜底；
     * uuidTerms 受 TermsQuery 词数上限约束，超限截断并 WARN。</p>
     */
    private void applyRefMainConditions(QuerySpec.Builder builder, RefResolution resolution) {
        List<TsColumnValue> uuidTerms = new ArrayList<>();
        for (String uuid : resolution.displays().keySet()) {
            if (uuidTerms.size() >= MAX_IN_TERMS) {
                log.warn("[query][ref] 命中 uuid 集超出 TermsQuery 词数上限 {}，截断: attrCode={}, 全集大小={}",
                        MAX_IN_TERMS, resolution.attrCode(), resolution.displays().size());
                break;
            }
            uuidTerms.add(TsColumnValue.of(uuid));
        }
        builder.nestedTerms("relations", "uuid", uuidTerms);
        builder.nestedTerm("relations", "attribute_code", TsColumnValue.of(resolution.attrCode()));
    }

    /**
     * 跨 Nested 元素幽灵行后置过滤（评审项 3）：仅保留 relations 中存在同一元素
     * 同时满足 attribute_code==attrCode 且 uuid∈命中集 的条目（每个 ref 条件均需满足）。
     *
     * @return 被剔除的条目数（调用方据此修正 total）
     */
    private int removeRefGhostRows(List<Map<String, Object>> items, List<RefResolution> resolutions) {
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!refSameElementMatch(items.get(i), resolutions)) {
                items.remove(i);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[query][ref] 后置过滤剔除跨 Nested 元素误命中条目: {} 条", removed);
        }
        return removed;
    }

    /** 单条目同元素匹配判定：每个 ref 条件均须存在同一 relations 元素同时满足两子字段 */
    private boolean refSameElementMatch(Map<String, Object> item, List<RefResolution> resolutions) {
        Object relationsObj = item.get("relations");
        if (!(relationsObj instanceof List<?> relations)) {
            return false;
        }
        for (RefResolution resolution : resolutions) {
            boolean matched = false;
            for (Object entryObj : relations) {
                if (!(entryObj instanceof Map<?, ?> entry)) {
                    continue;
                }
                if (resolution.attrCode().equals(String.valueOf(entry.get("attribute_code")))
                        && resolution.displays().containsKey(String.valueOf(entry.get("uuid")))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /** 实体行展示字段取值（attributes JSON 取 displayField，缺失返回 null） */
    private Object displayValue(TsRow row, String displayField) {
        TsColumnValue column = row.getColumn("attributes");
        if (column == null || column.getType() != TsColumnValue.Type.STRING) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(column.asString()).get(displayField);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ref_display 回显装配（RD-09，seed-stage4 S9 契约）：逐条目按 relations[].attribute_code
     * + 目标 uuid 匹配前置解析的展示载荷，合并为 {@code ref_display = {attrCode: {displayField: 值}}}；
     * 无任何 ref 命中关系的条目不附 ref_display 键。
     */
    @SuppressWarnings("unchecked")
    private void attachRefDisplay(List<Map<String, Object>> items, List<RefResolution> resolutions) {
        for (Map<String, Object> item : items) {
            Object relationsObj = item.get("relations");
            if (!(relationsObj instanceof List<?> relations)) {
                continue;
            }
            Map<String, Object> refDisplay = null;
            for (RefResolution resolution : resolutions) {
                for (Object entryObj : relations) {
                    if (!(entryObj instanceof Map<?, ?> entry)) {
                        continue;
                    }
                    if (!resolution.attrCode().equals(String.valueOf(entry.get("attribute_code")))) {
                        continue;
                    }
                    Map<String, Object> display = resolution.displays()
                            .get(String.valueOf(entry.get("uuid")));
                    if (display == null) {
                        continue;
                    }
                    if (refDisplay == null) {
                        refDisplay = new LinkedHashMap<>();
                    }
                    refDisplay.merge(resolution.attrCode(), new LinkedHashMap<>(display),
                            (existing, addition) -> {
                                ((Map<String, Object>) existing).putAll((Map<String, Object>) addition);
                                return existing;
                            });
                }
            }
            if (refDisplay != null) {
                item.put("ref_display", refDisplay);
            }
        }
    }

    /** 主表候选扫描：relations[].attribute_code 命中行提取 {目标编码 → uuid 集}（≤ REF_SCAN_LIMIT 行） */
    private Map<String, java.util.LinkedHashSet<String>> scanRefCandidates(Target target, String attrCode) {
        Map<String, java.util.LinkedHashSet<String>> candidates = new LinkedHashMap<>();
        String token = null;
        int scanned = 0;
        while (scanned < REF_SCAN_LIMIT) {
            QuerySpec.Builder cb = QuerySpec.builder();
            cb.nestedTerm("relations", "attribute_code", TsColumnValue.of(attrCode));
            injectActiveStatusFilter(cb, target);
            cb.limit(QuerySpec.MAX_LIMIT).getTotalCount(false);
            if (token != null) {
                cb.nextToken(token);
            }
            SearchResult result = template.search(target.table(), target.index(), cb.build());
            for (TsRow row : result.getRows()) {
                collectRefCandidates(row, attrCode, candidates);
            }
            scanned += result.getRows().size();
            if (!result.isHasNext() || result.getRows().isEmpty()) {
                break;
            }
            token = result.getNextToken();
        }
        return candidates;
    }

    /** 单行 relations JSON 解析：attribute_code 命中条目按目标编码归集 uuid */
    private void collectRefCandidates(TsRow row, String attrCode,
                                      Map<String, java.util.LinkedHashSet<String>> candidates) {
        Object parsed = parseJsonColumn(row.getColumn("relations"));
        if (!(parsed instanceof List<?> entries)) {
            return;
        }
        for (Object entryObj : entries) {
            if (!(entryObj instanceof Map<?, ?> entry)) {
                continue;
            }
            if (!attrCode.equals(String.valueOf(entry.get("attribute_code")))) {
                continue;
            }
            Object code = entry.get("code");
            Object uuid = entry.get("uuid");
            if (code == null || uuid == null) {
                continue;
            }
            candidates.computeIfAbsent(String.valueOf(code), k -> new java.util.LinkedHashSet<>())
                    .add(String.valueOf(uuid));
        }
    }

    /** 目标实体有效性：status ∈ {1,100}（逻辑删目标不参与关联命中） */
    private boolean isTargetActive(TsRow row) {
        TsColumnValue status = row.getColumn("status");
        if (status == null || status.getType() != TsColumnValue.Type.LONG) {
            return true;
        }
        long value = status.asLong();
        return value == 1L || value == 100L;
    }

    /** 展示字段匹配：attributes JSON 取 display_field 值，eq 精确 / contains 包含 */
    private boolean displayMatches(TsRow row, String displayField, String op, String value) {
        TsColumnValue column = row.getColumn("attributes");
        if (column == null || column.getType() != TsColumnValue.Type.STRING) {
            return false;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(column.asString()).get(displayField);
        } catch (Exception e) {
            return false;
        }
        if (node == null || node.isNull() || node.isContainerNode()) {
            return false;
        }
        String text = node.asText();
        return "eq".equals(op) ? text.equals(value) : text.contains(value);
    }

    // ==================== 值类型化 ====================

    private void requireStringField(Target target, String field, String op) {
        if (target.numericFields().contains(field)) {
            throw invalid(op + " 仅支持字符串字段: " + field);
        }
    }

    private String requireStringValue(String field, Object value) {
        if (!(value instanceof String str) || str.isEmpty()) {
            throw invalid("条件值须为非空字符串: " + field);
        }
        return str;
    }

    /** 数值字段强校验 + 通用列值类型化 */
    private TsColumnValue toColumnTyped(Target target, String field, Object value) {
        TsColumnValue column = toColumn(field, value);
        if (target.numericFields().contains(field)
                && column.getType() != TsColumnValue.Type.LONG
                && column.getType() != TsColumnValue.Type.DOUBLE) {
            throw invalid("字段 " + field + " 需要数值条件值");
        }
        return column;
    }

    private TsColumnValue toColumn(String field, Object value) {
        if (value == null) {
            throw invalid("条件值不可为空: " + field);
        }
        if (value instanceof Integer || value instanceof Long) {
            return TsColumnValue.of(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return TsColumnValue.of(number.doubleValue());
        }
        if (value instanceof Boolean bool) {
            return TsColumnValue.of(bool);
        }
        if (value instanceof String str) {
            return TsColumnValue.of(str);
        }
        throw invalid("不支持的条件值类型: " + field);
    }

    // ==================== 聚合（内存计算） ====================

    /** 聚合扫描结果（行集合 + 是否因达上限被截断，评审修复 Major-8） */
    private record ScanResult(List<TsRow> rows, boolean truncated) {
    }

    /**
     * 聚合计算：count 直接取 totalCount；sum/avg/group_by 续翻取行 ≤ 1000 内存计算；
     * 扫描达上限时响应附 {@code truncated=true} 标识（评审修复 Major-8：截断时聚合为近似值）。
     */
    private Map<String, Object> computeAggregations(
            Target target, List<FilterCondition> filters,
            List<AggregationDef> aggregations, long total) {
        if (aggregations == null || aggregations.isEmpty()) {
            return Map.of();
        }
        boolean needRows = aggregations.stream()
                .anyMatch(def -> !"count".equalsIgnoreCase(def.type()));
        ScanResult scan = needRows
                ? scanRowsForAggregation(target, filters)
                : new ScanResult(List.of(), false);
        List<TsRow> rows = scan.rows();
        Map<String, Object> result = new LinkedHashMap<>();
        for (AggregationDef def : aggregations) {
            String type = def.type().toLowerCase(Locale.ROOT);
            switch (type) {
                case "count" -> result.put(def.name(), List.of(Map.of("count", total)));
                case "sum" -> result.put(def.name(), List.of(Map.of("value", sum(rows, def.field()))));
                case "avg" -> result.put(def.name(), List.of(Map.of("value", avg(rows, def.field()))));
                case "group_by" -> result.put(def.name(), groupBy(rows, def.field()));
                default -> throw invalid("不支持的聚合类型: " + def.type());
            }
        }
        if (scan.truncated()) {
            result.put("truncated", true);
        }
        return result;
    }

    /** 聚合取行：按过滤条件续翻，最多 {@value #AGGREGATION_SCAN_LIMIT} 行；仍有剩余数据时标记截断 */
    private ScanResult scanRowsForAggregation(Target target, List<FilterCondition> filters) {
        List<TsRow> collected = new ArrayList<>();
        String token = null;
        boolean hasMore = false;
        while (collected.size() < AGGREGATION_SCAN_LIMIT) {
            QuerySpec.Builder builder = QuerySpec.builder();
            applyFilters(builder, target, filters);
            injectActiveStatusFilter(builder, target);
            builder.sortDesc("create_time")
                    .limit(Math.min(QuerySpec.MAX_LIMIT, AGGREGATION_SCAN_LIMIT - collected.size()))
                    .getTotalCount(false);
            if (token != null) {
                builder.nextToken(token);
            }
            SearchResult result = template.search(target.table(), target.index(), builder.build());
            collected.addAll(result.getRows());
            hasMore = result.isHasNext();
            if (!hasMore || result.getRows().isEmpty()) {
                break;
            }
            token = result.getNextToken();
        }
        return new ScanResult(collected, hasMore && collected.size() >= AGGREGATION_SCAN_LIMIT);
    }

    private double sum(List<TsRow> rows, String field) {
        double sum = 0d;
        for (TsRow row : rows) {
            Double value = numericValue(row, field);
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    private double avg(List<TsRow> rows, String field) {
        double sum = 0d;
        long count = 0L;
        for (TsRow row : rows) {
            Double value = numericValue(row, field);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    /** group_by：桶计数 + Top {@value #GROUP_BY_TOP_N} 截断（L7） */
    private List<Map<String, Object>> groupBy(List<TsRow> rows, String field) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (TsRow row : rows) {
            Object value = rawValue(row, field);
            if (value == null) {
                continue;
            }
            String key = String.valueOf(value);
            buckets.merge(key, 1L, Long::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        buckets.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(GROUP_BY_TOP_N)
                .forEach(entry -> {
                    Map<String, Object> bucket = new LinkedHashMap<>();
                    bucket.put("key", entry.getKey());
                    bucket.put("count", entry.getValue());
                    result.add(bucket);
                });
        return result;
    }

    /** 行字段数值提取（顶层列 / attributes.{code}） */
    private Double numericValue(TsRow row, String field) {
        Object value = rawValue(row, field);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 行字段原始值提取（顶层列 / attributes.{code} 动态属性） */
    private Object rawValue(TsRow row, String field) {
        if (field.startsWith(ATTRIBUTES_PREFIX)) {
            return attributeValue(row, field.substring(ATTRIBUTES_PREFIX.length()));
        }
        TsColumnValue column = row.getColumn(field);
        if (column == null) {
            return null;
        }
        return column.getObject();
    }

    private Object attributeValue(TsRow row, String attrCode) {
        TsColumnValue column = row.getColumn("attributes");
        if (column == null || column.getType() != TsColumnValue.Type.STRING) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(column.asString()).get(attrCode);
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isIntegralNumber()) {
                return node.asLong();
            }
            if (node.isNumber()) {
                return node.asDouble();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            return node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 行投影 ====================

    /** TsRow → 响应条目（attributes/relations 反序列化回 JSON 结构） */
    private Map<String, Object> projectRow(TsRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("uuid", row.getPrimaryKey().get("uuid"));
        for (Map.Entry<String, TsColumnValue> entry : row.getColumns().entrySet()) {
            String name = entry.getKey();
            TsColumnValue column = entry.getValue();
            if ("attributes".equals(name) || "relations".equals(name)) {
                item.put(name, parseJsonColumn(column));
            } else {
                item.put(name, column.getObject());
            }
        }
        return item;
    }

    private Object parseJsonColumn(TsColumnValue column) {
        if (column.getType() != TsColumnValue.Type.STRING) {
            return null;
        }
        try {
            return objectMapper.readValue(column.asString(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 工具 ====================

    /** 非法 DSL 统一 40002 */
    private static BizException invalid(String message) {
        return new BizException(ErrorCode.TYPE_INVALID, message);
    }
}
