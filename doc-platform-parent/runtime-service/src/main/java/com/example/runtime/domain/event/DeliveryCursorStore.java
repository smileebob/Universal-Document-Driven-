package com.example.runtime.domain.event;

import java.util.Optional;

/**
 * 每单据投递 checkpoint / 租约存储（EV-02）。
 */
public interface DeliveryCursorStore {

    /** 查询游标（不存在返回 empty）。 */
    Optional<DocDeliveryCursor> find(String partitionKey, String docUuid);

    /**
     * 读取或初始化游标（last_sent_seq=0）。
     */
    DocDeliveryCursor getOrCreate(String partitionKey, String docUuid);

    /**
     * CAS 认领投递租约。
     *
     * @param cursor     读取到的游标
     * @param owner      实例标识
     * @param leaseUntil 租约到期
     * @return true=认领成功
     */
    boolean claim(DocDeliveryCursor cursor, String owner, long leaseUntil);

    /**
     * 推进 checkpoint（仅租约持有者且 last_sent_seq 未变时）。
     *
     * @return true=推进成功
     */
    boolean advance(DocDeliveryCursor cursor, long sentSeq, String owner);

    /** 死信阻断该单据序列并清租约。 */
    void block(DocDeliveryCursor cursor, long eventSeq, String reason, String owner);

    /** 手动重发前解除阻断。 */
    void unblock(String partitionKey, String docUuid);

    /** 释放租约（不推进 checkpoint）。 */
    void release(DocDeliveryCursor cursor, String owner);
}
