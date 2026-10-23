package com.example.runtime.domain.event;

/**
 * 单据不可变操作日志（doc_data 同表同分区日志行，03 §4）。
 *
 * <p>主键：{@code partition_key + uuid(op:{docUuid}:{operationId})}。日志行与
 * 主行在同一 OTS 局部事务内提交；历史/outbox/审计由投影任务从本行幂等重建。</p>
 */
public class DocOperationLog {

    /** 业务行标记 */
    public static final long RECORD_KIND_DOC = 0L;

    /** 操作日志行标记 */
    public static final long RECORD_KIND_OPERATION = 1L;

    /** 投影待处理 */
    public static final long PROJECTION_PENDING = 0L;

    /** 投影完成 */
    public static final long PROJECTION_DONE = 1L;

    /** 投影重试耗尽，待人工 */
    public static final long PROJECTION_MANUAL = 2L;

    private String partitionKey;
    private String logUuid;
    private String operationId;
    private String docCode;
    private String docUuid;
    private String operation;
    /** 业务事件名（如 submit/audit；历史行 operation 沿用该值） */
    private String event;
    private long schemaVersion;
    private String requestDigest;
    private long expectedVersion;
    private long versionAfter;
    private Long fromStatus;
    private Long toStatus;
    private String eventId;
    private String eventType;
    private Long eventSeq;
    private Long previousEventSeq;
    private String historyJson;
    private String auditJson;
    private String eventJson;
    private String sagaId;
    private String operator;
    private String traceId;
    private long occurredAt;
    private long projectionStatus;
    private long projectionAttempts;
    private long nextProjectionTime;
    private String projectionLeaseOwner;
    private long projectionLeaseUntil;
    private String lastProjectionError;
    private long createTime;

    public boolean hasEvent() {
        return eventType != null && !eventType.isBlank();
    }

    public boolean isProjectionDone() {
        return projectionStatus == PROJECTION_DONE;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    public String getLogUuid() {
        return logUuid;
    }

    public void setLogUuid(String logUuid) {
        this.logUuid = logUuid;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
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

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public long getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(long schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public void setRequestDigest(String requestDigest) {
        this.requestDigest = requestDigest;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public long getVersionAfter() {
        return versionAfter;
    }

    public void setVersionAfter(long versionAfter) {
        this.versionAfter = versionAfter;
    }

    public Long getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(Long fromStatus) {
        this.fromStatus = fromStatus;
    }

    public Long getToStatus() {
        return toStatus;
    }

    public void setToStatus(Long toStatus) {
        this.toStatus = toStatus;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getEventSeq() {
        return eventSeq;
    }

    public void setEventSeq(Long eventSeq) {
        this.eventSeq = eventSeq;
    }

    public Long getPreviousEventSeq() {
        return previousEventSeq;
    }

    public void setPreviousEventSeq(Long previousEventSeq) {
        this.previousEventSeq = previousEventSeq;
    }

    public String getHistoryJson() {
        return historyJson;
    }

    public void setHistoryJson(String historyJson) {
        this.historyJson = historyJson;
    }

    public String getAuditJson() {
        return auditJson;
    }

    public void setAuditJson(String auditJson) {
        this.auditJson = auditJson;
    }

    public String getEventJson() {
        return eventJson;
    }

    public void setEventJson(String eventJson) {
        this.eventJson = eventJson;
    }

    public String getSagaId() {
        return sagaId;
    }

    public void setSagaId(String sagaId) {
        this.sagaId = sagaId;
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

    public long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(long occurredAt) {
        this.occurredAt = occurredAt;
    }

    public long getProjectionStatus() {
        return projectionStatus;
    }

    public void setProjectionStatus(long projectionStatus) {
        this.projectionStatus = projectionStatus;
    }

    public long getProjectionAttempts() {
        return projectionAttempts;
    }

    public void setProjectionAttempts(long projectionAttempts) {
        this.projectionAttempts = projectionAttempts;
    }

    public long getNextProjectionTime() {
        return nextProjectionTime;
    }

    public void setNextProjectionTime(long nextProjectionTime) {
        this.nextProjectionTime = nextProjectionTime;
    }

    public String getProjectionLeaseOwner() {
        return projectionLeaseOwner;
    }

    public void setProjectionLeaseOwner(String projectionLeaseOwner) {
        this.projectionLeaseOwner = projectionLeaseOwner;
    }

    public long getProjectionLeaseUntil() {
        return projectionLeaseUntil;
    }

    public void setProjectionLeaseUntil(long projectionLeaseUntil) {
        this.projectionLeaseUntil = projectionLeaseUntil;
    }

    public String getLastProjectionError() {
        return lastProjectionError;
    }

    public void setLastProjectionError(String lastProjectionError) {
        this.lastProjectionError = lastProjectionError;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
