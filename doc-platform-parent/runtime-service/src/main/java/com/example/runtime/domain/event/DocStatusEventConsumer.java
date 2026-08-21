package com.example.runtime.domain.event;

import java.util.Map;

/**
 * doc-status-events 消费契约（依赖倒置端口，design/07 §2.2）：
 * infrastructure 消息监听器经本接口委托 service 层实现，
 * 保持 infrastructure 不直接依赖 service（ArchUnit 分层门禁）。
 */
public interface DocStatusEventConsumer {

    /**
     * 消费一条 doc-status 事件。
     *
     * @param body         消息体（design/07 v1.1 信封 JSON）
     * @param userProps    用户属性（event_id/event_type/traceparent），可空
     * @param consumerMode 消费模式标识（mock/rocketmq，证据标注用）
     * @return true=首次消费落地；false=重复跳过
     */
    boolean consume(String body, Map<String, String> userProps, String consumerMode);
}
