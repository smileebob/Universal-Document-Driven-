package com.example.runtime.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.model.ApiResponse;
import com.example.runtime.domain.event.EventOutboxStore;
import com.example.runtime.domain.event.OutboxEvent;
import com.example.runtime.service.event.DocStatusEventConsumeService;
import com.example.runtime.service.event.OutboxRelayService;

/**
 * 事件运营接口（P-OP-03，任务清单 4）：
 * <ul>
 *   <li>GET /api/v1/event/retry：滞留/失败（待人工）列表；</li>
 *   <li>POST /api/v1/event/retry/{eventId}/resend：手动重发（复位即投）；</li>
 *   <li>GET /api/v1/event/consumed：消费证据（无真实订阅方的落地证明）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/event")
@RequireRoles(PlatformRoles.PLATFORM_ADMIN) // 运营接口限平台管理员（prd/06 §八：事件重发—）
public class EventRetryController {

    private final EventOutboxStore outboxStore;
    private final OutboxRelayService relayService;
    private final DocStatusEventConsumeService consumeService;

    public EventRetryController(EventOutboxStore outboxStore,
                                OutboxRelayService relayService,
                                DocStatusEventConsumeService consumeService) {
        this.outboxStore = outboxStore;
        this.relayService = relayService;
        this.consumeService = consumeService;
    }

    /**
     * 可重发事件列表（send_status=2 待人工，或 retry_count&gt;0 滞留待发）。
     *
     * @param limit 页长（≤100）
     * @return 事件列表（create_time 升序）
     */
    @GetMapping("/retry")
    public ApiResponse<Object> listRetryable(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<OutboxEvent> events = outboxStore.listRetryable(limit);
        List<Map<String, Object>> items = events.stream().map(this::toView).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        return ApiResponse.ok(data);
    }

    /**
     * 手动重发：复位 send_status=0 / retry_count=0 / next_retry_time=now，
     * 并即时入队投递。
     *
     * @param eventId 事件 ID
     * @return 重发受理结果
     */
    @PostMapping("/retry/{eventId}/resend")
    public ApiResponse<Object> resend(@PathVariable("eventId") String eventId) {
        OutboxEvent event = outboxStore.findByEventId(eventId).orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "事件不存在: " + eventId));
        if (event.getSendStatus() == 1L) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "事件已发送，无需重发: " + eventId);
        }
        long now = System.currentTimeMillis();
        outboxStore.resetForResend(event, now);
        relayService.enqueue(event.getPartitionKey(), event.getEventId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event_id", eventId);
        data.put("accepted", true);
        return ApiResponse.ok(data);
    }

    /**
     * 消费证据查询（本地无真实订阅方时的消费落地证明）。
     *
     * @param limit 条数（≤500）
     * @return 消费证据列表
     */
    @GetMapping("/consumed")
    public ApiResponse<Object> consumed(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<Map<String, Object>> evidences = consumeService.evidences(limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", evidences.size());
        data.put("items", evidences);
        return ApiResponse.ok(data);
    }

    /** outbox 行 → snake_case 视图 */
    private Map<String, Object> toView(OutboxEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("event_id", event.getEventId());
        view.put("event_type", event.getEventType());
        view.put("target_code", event.getTargetCode());
        view.put("target_uuid", event.getTargetUuid());
        view.put("send_status", event.getSendStatus());
        view.put("retry_count", event.getRetryCount());
        view.put("last_error", event.getLastError());
        view.put("next_retry_time", event.getNextRetryTime());
        view.put("create_time", event.getCreateTime());
        return view;
    }
}
