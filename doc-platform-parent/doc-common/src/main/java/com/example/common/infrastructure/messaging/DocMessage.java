package com.example.common.infrastructure.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台消息门面模型（防腐层自有契约，F-06：业务代码不感知 RocketMQ SDK 类型）。
 *
 * <p>双模式（{@code doc.mq.mode=mock|rocketmq}）共用本模型：mock 模式进程内
 * 仿真分发，rocketmq 模式翻译为 RocketMQTemplate 发送参数（destination =
 * {@code topic:tag}，ORDERLY 以 shardingKey 作 hashKey，userProperties 经
 * MessageBuilder headers 透传）。</p>
 */
public final class DocMessage {

    /** Topic（DocMqConstants.Topics） */
    private String topic;

    /** Tag（业务对象维度过滤，可为空=全订阅） */
    private String tag;

    /** 顺序分片键（ORDERLY 模式必填，如 doc_uuid） */
    private String shardingKey;

    /** 消息体（JSON 字符串） */
    private String body;

    /** 用户属性（traceparent / event_id 等，rocketmq 模式写 userProperties） */
    private Map<String, String> userProperties = new LinkedHashMap<>();

    /** 语义模式（默认 NORMAL） */
    private MessageMode mode = MessageMode.NORMAL;

    public DocMessage() {
    }

    public DocMessage(String topic, String tag, String shardingKey, String body, MessageMode mode) {
        this.topic = topic;
        this.tag = tag;
        this.shardingKey = shardingKey;
        this.body = body;
        this.mode = mode == null ? MessageMode.NORMAL : mode;
    }

    /** destination（rocketmq 模式 {@code topic:tag}；无 tag 仅 topic） */
    public String destination() {
        return tag == null || tag.isBlank() ? topic : topic + ":" + tag;
    }

    public DocMessage withUserProperty(String key, String value) {
        if (key != null && value != null) {
            userProperties.put(key, value);
        }
        return this;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getShardingKey() {
        return shardingKey;
    }

    public void setShardingKey(String shardingKey) {
        this.shardingKey = shardingKey;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Map<String, String> getUserProperties() {
        return userProperties;
    }

    public void setUserProperties(Map<String, String> userProperties) {
        this.userProperties = userProperties == null ? new LinkedHashMap<>() : userProperties;
    }

    public MessageMode getMode() {
        return mode;
    }

    public void setMode(MessageMode mode) {
        this.mode = mode == null ? MessageMode.NORMAL : mode;
    }

    @Override
    public String toString() {
        return "DocMessage{topic=" + topic + ", tag=" + tag + ", mode=" + mode
                + ", shardingKey=" + shardingKey + ", bodyLen=" + (body == null ? 0 : body.length()) + "}";
    }
}
