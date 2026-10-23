package com.example.runtime.domain.saga;

import java.util.List;
import java.util.Optional;

/**
 * Saga 存储端口（saga_execution / saga_dead_letter，TX-02/03）。
 *
 * <p>实现位于 infrastructure 层（TablestoreSagaStore）。saga_execution
 * V1 基线无多元索引（V 文件不可变，TS-06），崩溃恢复经 getRange 全表扫描
 * （行数百级规模可接受，任务清单 3「或分区扫描」口径）。</p>
 */
public interface SagaStore {

    /**
     * 幂等创建执行记录（EXPECT_NOT_EXIST）。
     *
     * @param execution 执行记录（status=EXECUTING）
     * @return true=本次创建成功；false=saga_id 已存在（幂等命中）
     */
    boolean createIfAbsent(SagaExecution execution);

    /**
     * 按 sagaId 查询执行记录（主键点查，强一致）。
     *
     * @param sagaId Saga 实例 ID
     * @return 记录（不存在返回 empty）
     */
    Optional<SagaExecution> find(String sagaId);

    /**
     * 更新执行状态与进度（无条件 UpdateRow，单 saga 由流转锁/恢复任务串行）。
     *
     * @param execution 执行记录（status/currentStepSeq/stepLogs/updateTime 生效）
     */
    void update(SagaExecution execution);

    /**
     * 扫描滞留 EXECUTING 记录（崩溃恢复入口，全表扫描过滤）。
     *
     * @param updateTimeBefore update_time 上界（含），即滞留判定时刻
     * @return EXECUTING 且 update_time ≤ 上界的记录
     */
    List<SagaExecution> scanStuckExecuting(long updateTimeBefore);

    /**
     * 扫描滞留 ACTIONS_SUCCEEDED 记录（外部步骤已完成、本地提交待收口）。
     *
     * @param updateTimeBefore update_time 上界（含）
     * @return ACTIONS_SUCCEEDED 且 update_time ≤ 上界的记录
     */
    List<SagaExecution> scanActionsSucceeded(long updateTimeBefore);

    /**
     * 扫描全部可自动恢复的非终态记录（EXECUTING / ACTIONS_SUCCEEDED /
     * COMMIT_PENDING / COMPENSATING）。
     *
     * @param updateTimeBefore update_time 上界（含）
     * @return 非终态且滞留的记录
     */
    List<SagaExecution> scanNonTerminal(long updateTimeBefore);

    /**
     * 恢复租约认领：仅当 status/lease_owner 与读取值一致时写入租约。
     * 多实例恢复任务据此串行推进同一 Saga。
     *
     * @param execution         读取到的执行记录
     * @param owner             实例标识
     * @param leaseUntil        租约到期时间
     * @param expectedUpdateTime 读取到的 update_time（CAS 辅助）
     * @return true=认领成功
     */
    boolean claim(SagaExecution execution, String owner, long leaseUntil, long expectedUpdateTime);

    /**
     * 当前状态查询（幂等预检查）。
     *
     * @param sagaId Saga 实例 ID
     * @return 状态（记录缺失返回 null）
     */
    SagaStatus status(String sagaId);

    /**
     * 释放恢复租约（仅 lease_owner 匹配时；不覆盖业务状态）。
     *
     * @param sagaId    Saga 实例 ID
     * @param owner     实例标识
     * @param updateTime 本次释放时间
     * @return true=释放成功
     */
    boolean releaseLease(String sagaId, String owner, long updateTime);

    /** 保存死信记录（整行覆盖写，同 saga 仅一条） */
    void saveDeadLetter(SagaDeadLetter deadLetter);

    /**
     * 死信分页查询（全表扫描 + 过滤，行数小规模）。
     *
     * @param handleStatus 处置状态过滤（null=全部）
     * @param offset       偏移
     * @param limit        页长（≤100）
     * @return 死信列表（create_time 降序）
     */
    List<SagaDeadLetter> listDeadLetters(Long handleStatus, int offset, int limit);

    /** 死信总数（分页展示用） */
    long countDeadLetters(Long handleStatus);

    /**
     * 查询死信。
     *
     * @param sagaId Saga 实例 ID
     * @return 死信记录
     */
    Optional<SagaDeadLetter> findDeadLetter(String sagaId);
}
