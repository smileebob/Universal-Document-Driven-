package com.example.runtime.service.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.example.runtime.domain.event.DocStatusEventConsumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * doc-status-events 消费处理（EV-02 FIFO 消费骨架）：
 * event_id 去重 → 记录消费证据 → 日志落痕。
 *
 * <p>本地无真实订阅方（design/07：平台内下游联动为 P1 规划），本期以
 * 日志 + 内存消费证据（环形 500 条，GET /api/v1/event/consumed 可查）
 * 作为消费落地证明；消费失败抛出异常由容器按 MQ 语义重试
 * （rocketmq ORDERLY 挂起队列，mock 模式不重投 BC-04）。</p>
 *
 * <p>链路续接（design/11 §4.1 / 07 号 6.5）：消费入口从 userProperties
 * {@code traceparent} 恢复 MDC traceId，消费日志携带生产侧链路 ID；
 * 证据同步落 {@code trace_id} 字段，消费结束清理 MDC 防线程复用串链。</p>
 */
@Component
public class DocStatusEventConsumeService implements DocStatusEventConsumer {

    /** 消费证据环形上限 */
    private static final int EVIDENCE_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(DocStatusEventConsumeService.class);

    private final EventDeduplicator deduplicator;
    private final ObjectMapper objectMapper;

    /** 消费证据环形缓冲（eventId → 记录，插入序） */
    private final List<Map<String, Object>> evidences =
            java.util.Collections.synchronizedList(new ArrayList<>());

    public DocStatusEventConsumeService(EventDeduplicator deduplicator, ObjectMapper objectMapper) {
        this.deduplicator = deduplicator;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费一条 doc-status 事件。
     *
     * @param body         消息体（design/07 v1.1 信封 JSON）
     * @param userProps    用户属性（event_id/event_type/traceparent）
     * @param consumerMode 消费模式标识（mock/rocketmq，证据标注用）
     * @return true=首次消费落地；false=重复跳过
     */
    @Override
    public boolean consume(String body, Map<String, String> userProps, String consumerMode) {
        String traceparent = userProps == null ? null : userProps.get("traceparent");
        boolean traceBound = false;
        if (traceparent != null && !traceparent.isBlank()) {
            // 消费入口恢复生产侧链路（design/11 §4.1：MQ 不自动传播，经 userProperties 续链）
            MDC.put("traceId", traceparent);
            traceBound = true;
        }
        try {
            return doConsume(body, userProps, consumerMode, traceparent);
        } finally {
            if (traceBound) {
                MDC.remove("traceId");
            }
        }
    }

    /** 消费主体（MDC 已绑定 traceparent 时日志自动携带） */
    private boolean doConsume(String body, Map<String, String> userProps,
                              String consumerMode, String traceparent) {
        String eventId = userProps == null ? null : userProps.get("event_id");
        if (eventId == null || eventId.isBlank()) {
            eventId = extractEventId(body);
        }
        if (!deduplicator.tryFirstConsume(eventId)) {
            log.info("[doc-status-consume] 重复事件跳过: eventId={}", eventId);
            return false;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("event_id", eventId);
        evidence.put("event_type", userProps == null ? null : userProps.get("event_type"));
        evidence.put("trace_id", traceparent);
        evidence.put("mode", consumerMode);
        evidence.put("consumed_at", System.currentTimeMillis());
        evidence.put("body_summary", summarize(body));
        synchronized (evidences) {
            evidences.add(evidence);
            while (evidences.size() > EVIDENCE_LIMIT) {
                evidences.remove(0);
            }
        }
        log.info("[doc-status-consume] 事件消费落地（无真实订阅方，记录消费证据）: eventId={}, type={}",
                eventId, evidence.get("event_type"));
        return true;
    }

    /** 消费证据查询（运营验证用） */
    public List<Map<String, Object>> evidences(int limit) {
        synchronized (evidences) {
            int safeLimit = Math.min(Math.max(1, limit), EVIDENCE_LIMIT);
            int from = Math.max(0, evidences.size() - safeLimit);
            return new ArrayList<>(evidences.subList(from, evidences.size()));
        }
    }

    /** 从消息体提取 event_id（信封根字段） */
    private String extractEventId(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode eventId = node.path("event_id");
            return eventId.isTextual() ? eventId.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 消息体摘要（证据截断，≤200 字符） */
    private String summarize(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }
}
