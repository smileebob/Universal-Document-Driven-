package com.example.common.infrastructure.tablestore.query;

import java.util.List;

import com.example.common.infrastructure.tablestore.model.TsRow;

/**
 * 多元索引检索结果（SearchResponse 的防腐层映射）。
 */
public final class SearchResult {

    /** 未请求总数时的占位值 */
    public static final long TOTAL_COUNT_NOT_REQUESTED = -1L;

    private final List<TsRow> rows;

    /** 下一页游标（Base64 不透明串；null 表示无更多数据） */
    private final String nextToken;

    /** 命中总数（仅 getTotalCount=true 时有效，否则为 -1） */
    private final long totalCount;

    public SearchResult(List<TsRow> rows, String nextToken, long totalCount) {
        this.rows = List.copyOf(rows);
        this.nextToken = nextToken;
        this.totalCount = totalCount;
    }

    public List<TsRow> getRows() {
        return rows;
    }

    public String getNextToken() {
        return nextToken;
    }

    public boolean isHasNext() {
        return nextToken != null && !nextToken.isEmpty();
    }

    public long getTotalCount() {
        return totalCount;
    }
}
