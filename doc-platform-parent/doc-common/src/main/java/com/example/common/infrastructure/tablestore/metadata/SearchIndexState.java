package com.example.common.infrastructure.tablestore.metadata;

/**
 * 多元索引运行状态快照（隔离 SDK DescribeSearchIndexResponse）。
 *
 * @param indexName        查询的索引名
 * @param exists           是否存在
 * @param status           PENDING/RUNNING/FAILED/UNKNOWN（存在时）
 * @param syncPhase        FULL/INCR（存在时；未知为 null）
 * @param syncTimestamp    当前同步时间戳（未知为 null）
 * @param brotherIndexName 切流后的兄弟索引（旧物理索引，未切流为 null）
 */
public record SearchIndexState(String indexName, boolean exists, String status,
                               String syncPhase, Long syncTimestamp,
                               String brotherIndexName) {

    /** 已建成且完成全量同步（可切流）。 */
    public boolean ready() {
        return exists && !"FAILED".equals(status)
                && ("FULL".equals(syncPhase) || "INCR".equals(syncPhase));
    }
}
