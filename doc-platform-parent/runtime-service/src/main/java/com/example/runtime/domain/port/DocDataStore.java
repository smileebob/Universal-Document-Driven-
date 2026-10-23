package com.example.runtime.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocOperationContext;

/**
 * 单据数据存储端口（doc_data / doc_data_history / event_outbox / audit_log）。
 *
 * <p>真实性硬约束：OTS 局部事务为<b>表级</b>作用域。主行业务变更与同表同分区
 * 的不可变操作日志行在同一局部事务内原子提交；history/outbox/audit 不再在
 * 事务后 best-effort 补写，而由操作日志投影任务幂等重建（03 §4）。</p>
 */
public interface DocDataStore {

    /** 主键点查单据主行（强一致；不存在/已逻辑删返回 empty）。 */
    Optional<DocDataRecord> find(String docCode, String uuid);

    /**
     * 创建单据：主行 + 操作日志行同事务原子提交（EXPECT_NOT_EXIST）。
     *
     * @param record  主行数据（current_status=100 暂存）
     * @param context 冻结操作上下文（operationId/operator/trace/after 快照）
     */
    void create(DocDataRecord record, DocOperationContext context);

    /**
     * 可编辑态修改：主行 version/status CAS 与日志行同一局部事务。
     *
     * @param docCode       单据编码
     * @param uuid          实例标识
     * @param expectVersion 期望版本
     * @param expectStatus  期望当前状态
     * @param context       合并后快照/操作人/operationId
     * @return false=版本/状态条件冲突（调用方映射 40901）
     */
    boolean updateEditable(String docCode, String uuid, long expectVersion, long expectStatus,
                           DocOperationContext context);

    /**
     * 逻辑删：主行 version/status CAS 与日志行同一局部事务。
     *
     * @return false=条件冲突
     */
    boolean logicalDelete(String docCode, String uuid, long expectVersion, long expectStatus,
                          DocOperationContext context);

    /**
     * 状态变更历史读取（doc_data_history 伴生表，分区级 GetRange，seq 升序）。
     */
    List<Map<String, Object>> findHistory(String docCode, String uuid);

    /**
     * 流转写入集：主行 CAS（expectedVersion + fromStatus）与日志行同一事务原子提交。
     *
     * @param context 冻结上下文（operation=transition、eventType、operator、sagaId、
     *                before/after 快照、occurredAt）
     */
    TransitionOutcome commitTransition(String docCode, String uuid, long expectedVersion,
                                       long fromStatus, long toStatus,
                                       DocOperationContext context);

    /**
     * 在途操作锁：Saga 外部步骤执行前以版本 CAS 写入 operationId 所有权，
     * 普通编辑/删除/其他流转在事务内识别并拒绝（03 §5.1）。
     *
     * @return 成功返回加锁后的新版本；条件冲突返回 0
     */
    long acquireOperation(String docCode, String uuid, long expectVersion, long expectStatus,
                          String operationId, String sagaId, long leaseUntil);

    /** 释放在途锁（仅 operationId 匹配时；版本递增使旧快照失效）。 */
    boolean releaseOperation(String docCode, String uuid, String operationId, long updateTime);

    /**
     * 流转结果。
     *
     * @param version    流转后版本号
     * @param historySeq 历史行 seq（与 version 同值）
     * @param eventId    稳定事件 ID（无事件为 null）
     * @param eventSeq   同单据事件序号（无事件为 0）
     */
    record TransitionOutcome(long version, long historySeq, String eventId, long eventSeq) {
    }
}
