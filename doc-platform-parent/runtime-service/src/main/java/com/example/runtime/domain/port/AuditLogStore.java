package com.example.runtime.domain.port;

import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.runtime.domain.model.AuditEntry;

/**
 * 审计日志仓储端口（audit_log，只增不改，RD-12）。
 */
public interface AuditLogStore {

    /**
     * 追加一条审计日志（分区键 log_{hash6(log_id)}，design/03 §2.10）。
     *
     * @param entry 审计条目
     */
    void append(AuditEntry entry);

    /**
     * 审计查询（走 audit_log_index 多元索引；只增不改，仅暴露只读检索，RD-12）。
     *
     * @param spec 查询条件（term/range/sort/分页）
     * @return 检索结果（行 + nextToken + 总数）
     */
    SearchResult search(QuerySpec spec);
}
