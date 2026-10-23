package com.example.runtime.domain.event;

import java.util.List;
import java.util.Optional;

/**
 * 不可变操作日志读取/投影状态端口（03 §4.3）。
 *
 * <p>日志载荷列（快照/事件）只读不更新；仅投影状态、租约、重试列可推进。
 * 待处理扫描经 {@code doc_data_operation_index}，最终一致索引只用于发现候选，
 * 主键点查确认后才投影。</p>
 */
public interface DocOperationLogStore {

    /** 主键点查日志行（强一致）。 */
    Optional<DocOperationLog> find(String partitionKey, String logUuid);

    /**
     * 扫描到期待投影日志（record_kind=1 AND projection_status=0 AND
     * next_projection_time ≤ now）。
     */
    ScanPage scanPending(long now, int limit, String nextToken);

    /**
     * CAS 认领：仅当 projection_status=0 且 lease_owner/lease_until 与读取值一致
     * 时写入新租约。返回 false 表示已被其他实例认领或状态已推进。
     */
    boolean claim(DocOperationLog log, String owner, long now, long leaseUntil);

    /** 标记投影完成（仅租约持有者可推进）。 */
    void markProjected(DocOperationLog log, String owner);

    /**
     * 投影失败：attempts+1、退避时刻、错误摘要；达到上限置 PROJECTION_MANUAL。
     */
    void markProjectionFailure(DocOperationLog log, String owner, String error,
                               long nextTime, boolean manual);

    /** 按单据/时间窗扫描日志（重放入口，含已完成行）。 */
    List<DocOperationLog> scanByDoc(String docCode, String docUuid, long fromTime,
                                    long toTime, int limit);

    /** 待投影总数（监控用，索引最终一致）。 */
    long countPending(long now);

    /**
     * 扫描已投影日志（清理/审计重放用；projection_status=1 AND occurred_at ≤ before）。
     */
    ScanPage scanProjected(long before, int limit, String nextToken);

    /** 清理已投影且超过保留期的日志行（仅条件匹配时删除）。 */
    boolean deleteProjected(DocOperationLog log);

    /** 扫描页。 */
    record ScanPage(List<DocOperationLog> logs, String nextToken) {
    }
}
