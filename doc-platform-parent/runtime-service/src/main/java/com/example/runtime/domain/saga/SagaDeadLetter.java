package com.example.runtime.domain.saga;

/**
 * Saga 冲正失败死信记录（saga_dead_letter 表运行域模型，TX-03）。
 *
 * <p>真相源：死信处置状态以本表为准（design/07：saga-dead-letter-notify
 * 通知 Topic 本期默认关闭）。handle_status：0 待处理 / 1 已重试 / 2 已人工。</p>
 */
public class SagaDeadLetter {

    /** Saga 实例 ID */
    private String sagaId;

    /** 单据编码 */
    private String docCode;

    /** 单据实例标识 */
    private String docUuid;

    /** 起始/目标状态 */
    private long fromStatus;
    private long toStatus;

    /** 失败步骤 seq */
    private long failedStepSeq;

    /** 失败阶段：action=正向执行失败 / compensate=冲正失败 */
    private String failPhase;

    /** 失败原因（异常摘要） */
    private String errorReason;

    /** 已完成步骤与冲正响应快照 JSON（诊断用） */
    private String compensateResponses;

    /** 处置状态：0 待处理 / 1 已重试 / 2 已人工 */
    private long handleStatus;

    /** 处置人 */
    private String handler;

    /** 处置时间 */
    private Long handleTime;

    /** 处置备注 */
    private String handleRemark;

    /** 操作人（发起人） */
    private String operator;

    /** 创建时间 */
    private long createTime;

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

    public long getFailedStepSeq() {
        return failedStepSeq;
    }

    public void setFailedStepSeq(long failedStepSeq) {
        this.failedStepSeq = failedStepSeq;
    }

    public String getFailPhase() {
        return failPhase;
    }

    public void setFailPhase(String failPhase) {
        this.failPhase = failPhase;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    public String getCompensateResponses() {
        return compensateResponses;
    }

    public void setCompensateResponses(String compensateResponses) {
        this.compensateResponses = compensateResponses;
    }

    public long getHandleStatus() {
        return handleStatus;
    }

    public void setHandleStatus(long handleStatus) {
        this.handleStatus = handleStatus;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public Long getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Long handleTime) {
        this.handleTime = handleTime;
    }

    public String getHandleRemark() {
        return handleRemark;
    }

    public void setHandleRemark(String handleRemark) {
        this.handleRemark = handleRemark;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
