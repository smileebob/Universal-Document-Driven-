package com.example.runtime.domain.event;

import java.util.List;
import java.util.Optional;

/**
 * event_outbox 存储端口（EV-01/02 中继依赖，design/07 §四）。
 *
 * <p>写入侧：流转/建单局部事务内同分区落行（TablestoreDocDataStore）；
 * 本端口仅覆盖中继侧读取与状态推进。扫描经 event_outbox_index
 * （send_status + next_retry_time，limit≤100 + next_token 续翻）。</p>
 */
public interface EventOutboxStore {

    /**
     * 扫描待投递事件（send_status=0 AND next_retry_time ≤ now）。
     *
     * @param now       当前时间
     * @param limit     页长（≤100）
     * @param nextToken 续翻游标（首页 null）
     * @return 扫描结果（行 + 游标）
     */
    ScanPage scanPending(long now, int limit, String nextToken);

    /**
     * 主键点查（写后读强一致）。
     *
     * @param partitionKey 分区键
     * @param eventId      事件 ID
     * @return 事件行
     */
    Optional<OutboxEvent> find(String partitionKey, String eventId);

    /**
     * 按 event_id 全表定位（手动重发兜底路径，无 partition_key 时；行数小规模）。
     *
     * @param eventId 事件 ID
     * @return 事件行
     */
    Optional<OutboxEvent> findByEventId(String eventId);

    /**
     * CAS 抢占（防多实例重发）：仅当 next_retry_time == expectNextRetryTime
     * 时更新为 claimedNextRetryTime。
     *
     * @param event                 事件行
     * @param expectNextRetryTime   期望的 next_retry_time 旧值
     * @param claimedNextRetryTime  抢占后新值（now + 10min）
     * @return true=抢占成功；false=已被其他实例抢占
     */
    boolean casClaim(OutboxEvent event, long expectNextRetryTime, long claimedNextRetryTime);

    /** 标记已发送（send_status=1） */
    void markSent(OutboxEvent event);

    /**
     * 投递失败退避推进：retry_count+1、next_retry_time=退避时刻、last_error；
     * retry_count 达上限时置 send_status=2 待人工。
     *
     * @param event         事件行
     * @param nextRetryTime 下次投递时刻
     * @param lastError     失败原因
     * @param toManual      是否转待人工
     */
    void markRetry(OutboxEvent event, long nextRetryTime, String lastError, boolean toManual);

    /** 手动重发复位：send_status=0、retry_count=0、next_retry_time=now */
    void resetForResend(OutboxEvent event, long now);

    /**
     * 查询可重发列表（send_status=2 待人工，或 retry_count>0 的滞留待发）。
     *
     * @param limit 页长
     * @return 事件列表（create_time 升序）
     */
    List<OutboxEvent> listRetryable(int limit);

    /** 归档删除（已发送且超过保留期，7 天归档清理任务用） */
    void delete(OutboxEvent event);

    /**
     * 扫描已发送归档候选（send_status=1 AND create_time ≤ before，索引续翻）。
     *
     * @param before    创建时间上界
     * @param limit     页长
     * @param nextToken 游标
     * @return 扫描结果
     */
    ScanPage scanSent(long before, int limit, String nextToken);

    /** 扫描页（行 + 续翻游标） */
    record ScanPage(List<OutboxEvent> events, String nextToken) {
    }
}
