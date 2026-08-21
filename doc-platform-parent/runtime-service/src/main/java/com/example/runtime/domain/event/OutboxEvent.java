package com.example.runtime.domain.event;

/**
 * Outbox 待发事件运行域模型（event_outbox 表投影，design/03 §2.11 / design/07 §四）。
 *
 * <p>send_status：0 待发 / 1 已发 / 2 待人工（退避 8 级耗尽）。
 * CAS 抢占列 next_retry_time（多实例防重发）。</p>
 */
public class OutboxEvent {

    /** 分区键（与业务行同分区 {code}_{hash4(uuid)}） */
    private String partitionKey;

    /** 事件唯一 ID（主键第二列） */
    private String eventId;

    /** 事件类型（prd/12 §1.10：DOC_STATUS_CHANGED / DOC_CREATED / ENTITY_CHANGED） */
    private String eventType;

    /** 事件体 JSON（design/07 v1.1，≤64KB） */
    private String eventBody;

    /** 目标对象编码/实例（Tag / Sharding Key 来源） */
    private String targetCode;
    private String targetUuid;

    /** 投递状态：0 待发 / 1 已发 / 2 待人工 */
    private long sendStatus;

    /** 已重试次数 */
    private long retryCount;

    /** 最近失败原因 */
    private String lastError;

    /** 下次可投递时间（CAS 抢占列） */
    private long nextRetryTime;

    /** 创建时间 */
    private long createTime;

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
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

    public String getEventBody() {
        return eventBody;
    }

    public void setEventBody(String eventBody) {
        this.eventBody = eventBody;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = targetCode;
    }

    public String getTargetUuid() {
        return targetUuid;
    }

    public void setTargetUuid(String targetUuid) {
        this.targetUuid = targetUuid;
    }

    public long getSendStatus() {
        return sendStatus;
    }

    public void setSendStatus(long sendStatus) {
        this.sendStatus = sendStatus;
    }

    public long getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(long retryCount) {
        this.retryCount = retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public long getNextRetryTime() {
        return nextRetryTime;
    }

    public void setNextRetryTime(long nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
