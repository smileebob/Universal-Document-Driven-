package com.example.runtime.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.runtime.domain.model.DocDataRecord;

/**
 * 单据数据存储端口（doc_data / doc_data_history / event_outbox / audit_log
 * 同分区写入，design/03 §2.2 / §2.10 / §6.1 场景 3）。
 *
 * <p>实现位于 infrastructure 层（TablestoreDocDataStore）。OTS 局部事务为
 * 表级作用域（硬约束）：事务内仅原子提交 doc_data 主行，history/outbox/审计
 * 为事务提交后幂等补写（准最终一致，T1-T6 拆分见实现类注释）。</p>
 */
public interface DocDataStore {

    /**
     * 主键点查单据主行（强一致，TS-03）。
     *
     * @param docCode 单据编码
     * @param uuid    单据实例唯一标识
     * @return 主行（不存在/已逻辑删返回 empty）
     */
    Optional<DocDataRecord> find(String docCode, String uuid);

    /**
     * 创建单据（单表局部事务：主行 EXPECT_NOT_EXIST；创建历史行 seq=1
     * 与 DOC_CREATED outbox 行在事务提交后幂等补写）。
     *
     * @param record   主行数据（current_status=100 暂存）
     * @param operator 操作人
     */
    default void create(DocDataRecord record, String operator) {
        create(record, operator, null);
    }

    /**
     * 创建单据（traceId 重载：DOC_CREATED 事件信封写 traceparent）。
     *
     * @param record   主行数据
     * @param operator 操作人
     * @param traceId  链路追踪 ID（可空）
     */
    void create(DocDataRecord record, String operator, String traceId);

    /**
     * 可编辑态修改（current_status 快照 CAS + version 校验）。
     *
     * @param docCode           单据编码
     * @param uuid              实例标识
     * @param expectVersion     期望版本（乐观锁）
     * @param expectStatus      读取快照 current_status（条件列）
     * @param attributes        合并后属性
     * @param relations         合并后 ref 关系
     * @param updateTime        更新时间
     * @return false=条件冲突（调用方映射 40901）
     */
    boolean updateEditable(String docCode, String uuid, long expectVersion, long expectStatus,
                           Map<String, Object> attributes, List<Map<String, Object>> relations,
                           long updateTime);

    /**
     * 逻辑删（仅暂存态，deleted=1，version CAS）。
     *
     * @return false=条件冲突（调用方映射 40901）
     */
    boolean logicalDelete(String docCode, String uuid, long expectVersion, long updateTime);

    /**
     * 状态变更历史读取（doc_data_history 伴生表，分区级 GetRange，RD-08 只增不改）。
     *
     * @param docCode 单据编码
     * @param uuid    实例标识
     * @return snake_case 记录列表（seq 升序；单据无历史返回空列表）
     */
    List<Map<String, Object>> findHistory(String docCode, String uuid);

    /**
     * 流转写入集（单表局部事务原子提交主行 CAS；历史行 / outbox /
     * 同分区审计在事务提交后幂等补写，准最终一致）。
     *
     * @param docCode     单据编码
     * @param uuid        实例标识
     * @param fromStatus  期望当前状态（T3 CAS 条件列）
     * @param toStatus    目标状态
     * @param operator    操作人
     * @param event       事件名（如 submit/audit）
     * @param sagaId      Saga 实例 ID（无 Saga 时传 null；写事件 payload）
     * @return 流转结果（新版本号/历史 seq/outbox event_id）
     */
    TransitionOutcome commitTransition(String docCode, String uuid, long fromStatus,
                                       long toStatus, String operator, String event,
                                       String sagaId);

    /**
     * 流转结果（响应体与幂等快照消费）。
     *
     * @param version    流转后版本号
     * @param historySeq 历史行 seq（评审项 1 整改后与 version 同值：CAS 保护、
     *                   天然单调唯一，create 路径 version=1/seq=1 对齐）
     * @param eventId    T5 outbox 事件 ID（阶段三：事务提交后即时首发入队）
     */
    record TransitionOutcome(long version, long historySeq, String eventId) {
    }
}
