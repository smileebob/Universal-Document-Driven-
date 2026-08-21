package com.example.meta.domain.messaging;

import java.io.Serializable;
import java.util.UUID;

import com.example.common.constants.DocMqConstants;

/**
 * 配置缓存失效事件（BC-02 自包含消息模型，design/06 §5.3 / design/07 §3.3）。
 *
 * <p>消息体结构：{@code {event_id, event_type, timestamp,
 * payload:{cache_domain, code, version}}}。消费方仅凭消息体即可完成
 * 缓存失效，不依赖回查（BC-02）；version 用于乱序过滤（BC-05）。</p>
 */
public class SchemaInvalidationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一标识 */
    private final String eventId;

    /** 事件类型（SCHEMA_PUBLISHED / SCHEMA_DISABLED / CONFIG_CHANGED） */
    private final String eventType;

    /** 事件时间戳（UTC 毫秒） */
    private final long timestamp;

    /** 缓存域（CacheDomains 七类之一，同时作为消息 Tag） */
    private final String cacheDomain;

    /** 配置对象编码（menu_config 域取 target_key；无编码对象取固定串） */
    private final String code;

    /** 变更后版本号（乱序过滤水位） */
    private final long version;

    public SchemaInvalidationEvent(String eventType, String cacheDomain, String code, long version) {
        this(UUID.randomUUID().toString(), eventType, System.currentTimeMillis(), cacheDomain, code, version);
    }

    public SchemaInvalidationEvent(String eventId, String eventType, long timestamp,
                                   String cacheDomain, String code, long version) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.cacheDomain = cacheDomain;
        this.code = code;
        this.version = version;
    }

    /** 定义发布失效事件快捷构造 */
    public static SchemaInvalidationEvent published(String cacheDomain, String code, long version) {
        return new SchemaInvalidationEvent(DocMqConstants.EventTypes.SCHEMA_PUBLISHED, cacheDomain, code, version);
    }

    /** 定义停用失效事件快捷构造 */
    public static SchemaInvalidationEvent disabled(String cacheDomain, String code, long version) {
        return new SchemaInvalidationEvent(DocMqConstants.EventTypes.SCHEMA_DISABLED, cacheDomain, code, version);
    }

    /** 配置变更失效事件快捷构造 */
    public static SchemaInvalidationEvent configChanged(String cacheDomain, String code, long version) {
        return new SchemaInvalidationEvent(DocMqConstants.EventTypes.CONFIG_CHANGED, cacheDomain, code, version);
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getCacheDomain() {
        return cacheDomain;
    }

    public String getCode() {
        return code;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "SchemaInvalidationEvent{" + eventType + ", domain=" + cacheDomain
                + ", code=" + code + ", version=" + version + "}";
    }
}
