package com.example.runtime.domain.saga;

/**
 * Saga 执行主记录（saga_execution 表运行域模型，design/05 §saga_execution）。
 *
 * <p>主键：{@code saga_{hash4(saga_id)}} + saga_id；创建即幂等
 * （EXPECT_NOT_EXIST，崩溃恢复/重试不重复建行）。</p>
 */
public class SagaExecution {

    /** Saga 实例 ID（由流转幂等键派生，编排器归一） */
    private String sagaId;

    /** 单据编码 */
    private String docCode;

    /** 单据实例标识 */
    private String docUuid;

    /** 起始状态 */
    private long fromStatus;

    /** 目标状态 */
    private long toStatus;

    /** 规则/Saga 执行前读取的单据版本（恢复提交时的快照版本，防止提交陈旧数据） */
    private long expectedVersion;

    /** 执行状态 */
    private SagaStatus status;

    /** 当前步骤 seq（正向进度；冲正阶段保持失败步 seq） */
    private long currentStepSeq;

    /** 步骤执行日志 JSON（[{seq,phase,result,ts,error?}]，诊断/恢复用） */
    private String stepLogs;

    /** 事务计划快照 JSON（TxPlan 序列化；崩溃恢复/死信重试自持，不依赖配置现状） */
    private String txPlanJson;

    /** 操作人 */
    private String operator;

    /** 链路追踪 ID（MDC traceId 快照） */
    private String traceId;

    /** 创建时间（UTC 毫秒） */
    private long createTime;

    /** 更新时间（UTC 毫秒，崩溃恢复扫描依据） */
    private long updateTime;

    /** 恢复/收口租约持有者（空=未认领；多实例防并发推进） */
    private String leaseOwner;

    /** 租约到期时间（UTC 毫秒） */
    private long leaseUntil;

    public String getSagaId() {
        return sagaId;
    }

    public void setSagaId(String sagaId) {
        this.sagaId = sagaId;
    }

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }

    public String getDocUuid() {
        return docUuid;
    }

    public void setDocUuid(String docUuid) {
        this.docUuid = docUuid;
    }

    public long getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(long fromStatus) {
        this.fromStatus = fromStatus;
    }

    public long getToStatus() {
        return toStatus;
    }

    public void setToStatus(long toStatus) {
        this.toStatus = toStatus;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public SagaStatus getStatus() {
        return status;
    }

    public void setStatus(SagaStatus status) {
        this.status = status;
    }

    public long getCurrentStepSeq() {
        return currentStepSeq;
    }

    public void setCurrentStepSeq(long currentStepSeq) {
        this.currentStepSeq = currentStepSeq;
    }

    public String getStepLogs() {
        return stepLogs;
    }

    public void setStepLogs(String stepLogs) {
        this.stepLogs = stepLogs;
    }

    public String getTxPlanJson() {
        return txPlanJson;
    }

    public void setTxPlanJson(String txPlanJson) {
        this.txPlanJson = txPlanJson;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }
}
