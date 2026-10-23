package com.example.runtime.domain.event;

/**
 * 每单据事件投递游标（event_delivery_cursor，EV-02）。
 *
 * <p>last_sent_seq 之前的事件已确认投递；同一时刻仅持有租约者推进最早
 * 未确认 eventSeq。blocked_event_seq 非 0 表示死信阻断，后继事件不得越序。</p>
 */
public class DocDeliveryCursor {

    private String partitionKey;
    private String docUuid;
    private long lastSentSeq;
    private long blockedEventSeq;
    private String blockedReason;
    private String leaseOwner;
    private long leaseUntil;
    private long updateTime;

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    public String getDocUuid() {
        return docUuid;
    }

    public void setDocUuid(String docUuid) {
        this.docUuid = docUuid;
    }

    public long getLastSentSeq() {
        return lastSentSeq;
    }

    public void setLastSentSeq(long lastSentSeq) {
        this.lastSentSeq = lastSentSeq;
    }

    public long getBlockedEventSeq() {
        return blockedEventSeq;
    }

    public void setBlockedEventSeq(long blockedEventSeq) {
        this.blockedEventSeq = blockedEventSeq;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
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

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public boolean blocked() {
        return blockedEventSeq > 0;
    }
}
