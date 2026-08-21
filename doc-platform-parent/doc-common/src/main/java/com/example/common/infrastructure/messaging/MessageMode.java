package com.example.common.infrastructure.messaging;

/**
 * 消息语义模式（design/07 §2.2 Topic 语义映射）。
 *
 * <ul>
 *   <li>{@link #NORMAL}：普通消息（entity-change-events）；</li>
 *   <li>{@link #ORDERLY}：分区顺序消息（doc-status-events，Sharding Key = doc_uuid）；</li>
 *   <li>{@link #BROADCAST}：广播消息（schema-invalidation，每实例一份）。</li>
 * </ul>
 */
public enum MessageMode {

    /** 普通消息 */
    NORMAL,

    /** 分区顺序消息（同一 shardingKey 串行消费） */
    ORDERLY,

    /** 广播消息（每个消费实例各收一份） */
    BROADCAST
}
