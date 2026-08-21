package com.example.common.infrastructure.tablestore.ots;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import com.alicloud.openservices.tablestore.model.search.query.BoolQuery;
import com.alicloud.openservices.tablestore.model.search.query.ExistsQuery;
import com.alicloud.openservices.tablestore.model.search.query.MatchAllQuery;
import com.alicloud.openservices.tablestore.model.search.query.NestedQuery;
import com.alicloud.openservices.tablestore.model.search.query.PrefixQuery;
import com.alicloud.openservices.tablestore.model.search.query.Query;
import com.alicloud.openservices.tablestore.model.search.query.RangeQuery;
import com.alicloud.openservices.tablestore.model.search.query.ScoreMode;
import com.alicloud.openservices.tablestore.model.search.query.TermQuery;
import com.alicloud.openservices.tablestore.model.search.query.TermsQuery;
import com.alicloud.openservices.tablestore.model.search.query.WildcardQuery;
import com.alicloud.openservices.tablestore.model.search.sort.FieldSort;
import com.alicloud.openservices.tablestore.model.search.sort.Sort;
import com.alicloud.openservices.tablestore.model.search.sort.SortOrder;
import com.example.common.infrastructure.tablestore.query.QueryCondition;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SortField;

/**
 * QuerySpec → SearchQuery 结构化翻译（TS-01：一律 SDK 结构化对象构造，禁止拼接查询串）。
 */
final class OtsQueryTranslator {

    private OtsQueryTranslator() {
    }

    static com.alicloud.openservices.tablestore.model.search.SearchQuery translate(QuerySpec spec) {
        com.alicloud.openservices.tablestore.model.search.SearchQuery searchQuery =
                new com.alicloud.openservices.tablestore.model.search.SearchQuery();

        // 条件：AND 组合（BoolQuery.must）
        List<Query> must = spec.getMust().stream()
                .map(OtsQueryTranslator::translateCondition)
                .collect(Collectors.toList());
        if (must.isEmpty()) {
            searchQuery.setQuery(new MatchAllQuery());
        } else if (must.size() == 1) {
            searchQuery.setQuery(must.get(0));
        } else {
            BoolQuery boolQuery = new BoolQuery();
            boolQuery.setMustQueries(must);
            searchQuery.setQuery(boolQuery);
        }

        // 分页：limit ≤ 100 由 QuerySpec 构造侧强校验；深翻页走 next_token
        searchQuery.setLimit(spec.getLimit());
        searchQuery.setGetTotalCount(spec.isGetTotalCount());
        if (spec.getNextToken() != null && !spec.getNextToken().isEmpty()) {
            searchQuery.setToken(Base64.getDecoder().decode(spec.getNextToken()));
        } else if (!spec.getSorts().isEmpty()) {
            List<Sort.Sorter> sorters = new ArrayList<>();
            for (SortField sort : spec.getSorts()) {
                FieldSort fieldSort = new FieldSort(sort.getField());
                fieldSort.setOrder(sort.isAsc() ? SortOrder.ASC : SortOrder.DESC);
                sorters.add(fieldSort);
            }
            searchQuery.setSort(new Sort(sorters));
        }
        return searchQuery;
    }

    static String encodeToken(byte[] token) {
        return token == null || token.length == 0 ? null : Base64.getEncoder().encodeToString(token);
    }

    private static Query translateCondition(QueryCondition condition) {
        switch (condition.getKind()) {
            case TERM: {
                QueryCondition.Term term = (QueryCondition.Term) condition;
                TermQuery query = new TermQuery();
                query.setFieldName(term.getField());
                query.setTerm(OtsTypes.toSdkColumnValue(term.getValue()));
                return query;
            }
            case TERMS: {
                QueryCondition.Terms terms = (QueryCondition.Terms) condition;
                TermsQuery query = new TermsQuery();
                query.setFieldName(terms.getField());
                query.setTerms(terms.getValues().stream()
                        .map(OtsTypes::toSdkColumnValue)
                        .collect(Collectors.toList()));
                return query;
            }
            case WILDCARD: {
                QueryCondition.Wildcard wildcard = (QueryCondition.Wildcard) condition;
                WildcardQuery query = new WildcardQuery();
                query.setFieldName(wildcard.getField());
                query.setValue(wildcard.getPattern());
                return query;
            }
            case PREFIX: {
                QueryCondition.Prefix prefix = (QueryCondition.Prefix) condition;
                PrefixQuery query = new PrefixQuery();
                query.setFieldName(prefix.getField());
                query.setPrefix(prefix.getPrefix());
                return query;
            }
            case RANGE: {
                QueryCondition.Range range = (QueryCondition.Range) condition;
                RangeQuery query = new RangeQuery();
                query.setFieldName(range.getField());
                if (range.getFrom() != null) {
                    query.setFrom(OtsTypes.toSdkColumnValue(range.getFrom()), range.isFromInclusive());
                }
                if (range.getTo() != null) {
                    query.setTo(OtsTypes.toSdkColumnValue(range.getTo()), range.isToInclusive());
                }
                return query;
            }
            case EXISTS: {
                ExistsQuery query = new ExistsQuery();
                query.setFieldName(condition.getField());
                return query;
            }
            case NESTED_TERM: {
                QueryCondition.NestedTerm nested = (QueryCondition.NestedTerm) condition;
                TermQuery inner = new TermQuery();
                inner.setFieldName(nested.getField());
                inner.setTerm(OtsTypes.toSdkColumnValue(nested.getValue()));
                NestedQuery query = new NestedQuery();
                query.setPath(nested.getPath());
                query.setQuery(inner);
                query.setScoreMode(ScoreMode.None);
                return query;
            }
            case NESTED_TERMS: {
                QueryCondition.NestedTerms nested = (QueryCondition.NestedTerms) condition;
                TermsQuery inner = new TermsQuery();
                inner.setFieldName(nested.getField());
                inner.setTerms(nested.getValues().stream()
                        .map(OtsTypes::toSdkColumnValue)
                        .collect(Collectors.toList()));
                NestedQuery query = new NestedQuery();
                query.setPath(nested.getPath());
                query.setQuery(inner);
                query.setScoreMode(ScoreMode.None);
                return query;
            }
            case NESTED_WILDCARD: {
                QueryCondition.NestedWildcard nested = (QueryCondition.NestedWildcard) condition;
                WildcardQuery inner = new WildcardQuery();
                inner.setFieldName(nested.getField());
                inner.setValue(nested.getPattern());
                NestedQuery query = new NestedQuery();
                query.setPath(nested.getPath());
                query.setQuery(inner);
                query.setScoreMode(ScoreMode.None);
                return query;
            }
            default:
                throw new IllegalArgumentException("不支持的查询条件: " + condition.getKind());
        }
    }
}
