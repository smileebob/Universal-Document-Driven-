package com.example.runtime.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.model.ApiResponse;
import com.example.runtime.domain.saga.SagaDeadLetter;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStore;
import com.example.runtime.service.saga.SagaOrchestrator;

/**
 * Saga 死信人工处置接口（P-OP-02，任务清单 3）：
 * <ul>
 *   <li>GET /api/v1/saga/dead-letter：分页列表（handle_status 过滤）；</li>
 *   <li>POST /api/v1/saga/dead-letter/{sagaId}/retry：补完冲正重试；</li>
 *   <li>POST /api/v1/saga/dead-letter/{sagaId}/resolve：人工处置关闭。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/saga/dead-letter")
@RequireRoles(PlatformRoles.PLATFORM_ADMIN) // 运营接口限平台管理员（prd/06 §八：补偿/事件重发—）
public class SagaDeadLetterController {

    private final SagaStore sagaStore;
    private final SagaOrchestrator orchestrator;

    public SagaDeadLetterController(SagaStore sagaStore, SagaOrchestrator orchestrator) {
        this.sagaStore = sagaStore;
        this.orchestrator = orchestrator;
    }

    /**
     * 死信分页查询。
     *
     * @param handleStatus 处置状态过滤（可空=全部）
     * @param page         页码（1 起）
     * @param size         页长（≤100）
     * @return {@code {total, page, size, items[]}}
     */
    @GetMapping
    public ApiResponse<Object> list(@RequestParam(value = "handle_status", required = false) Long handleStatus,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        long total = sagaStore.countDeadLetters(handleStatus);
        List<SagaDeadLetter> letters = sagaStore.listDeadLetters(handleStatus,
                (safePage - 1) * safeSize, safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SagaDeadLetter letter : letters) {
            items.add(toView(letter));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("items", items);
        return ApiResponse.ok(data);
    }

    /**
     * 死信重试（补完冲正）。成功后死信 handle_status=1（已重试），
     * 执行记录转 COMPENSATED；仍失败保持待处理并返回 42202。
     *
     * @param sagaId Saga 实例 ID
     * @param userId 处置人（X-User-Id）
     * @return 处置结果
     */
    @PostMapping("/{sagaId}/retry")
    public ApiResponse<Object> retry(@PathVariable("sagaId") String sagaId,
                                     @RequestHeader(value = "X-User-Id", required = false) String userId) {
        SagaDeadLetter letter = requireLetter(sagaId);
        if (letter.getHandleStatus() == 2L) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "死信已人工关闭，不可重试: " + sagaId);
        }
        String handler = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        try {
            orchestrator.retryDeadLetter(letter, handler);
        } catch (SagaOrchestrator.SagaDeadException e) {
            throw new BizException(ErrorCode.TX_FAILED, "死信重试仍失败: " + e.getMessage(),
                    Map.of("saga_id", sagaId));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saga_id", sagaId);
        data.put("handle_status", 1L);
        data.put("message", "补完冲正成功");
        return ApiResponse.ok(data);
    }

    /**
     * 死信人工处置关闭（handle_status=2）。
     *
     * @param sagaId Saga 实例 ID
     * @param body   {@code {"remark": "..."}}（可空）
     * @param userId 处置人（X-User-Id）
     * @return 处置结果
     */
    @PostMapping("/{sagaId}/resolve")
    public ApiResponse<Object> resolve(@PathVariable("sagaId") String sagaId,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-User-Id", required = false) String userId) {
        SagaDeadLetter letter = requireLetter(sagaId);
        if (letter.getHandleStatus() == 2L) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "死信已人工关闭: " + sagaId);
        }
        String handler = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        String remark = body != null && body.get("remark") != null
                ? String.valueOf(body.get("remark")) : "";
        letter.setHandleStatus(2L);
        letter.setHandler(handler);
        letter.setHandleTime(System.currentTimeMillis());
        letter.setHandleRemark(remark);
        sagaStore.saveDeadLetter(letter);

        // 执行记录同步终态 DEAD（保持与死信一致，供运营视图）
        SagaExecution execution = sagaStore.find(sagaId).orElse(null);
        if (execution != null) {
            execution.setUpdateTime(System.currentTimeMillis());
            sagaStore.update(execution);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saga_id", sagaId);
        data.put("handle_status", 2L);
        data.put("handler", handler);
        return ApiResponse.ok(data);
    }

    private SagaDeadLetter requireLetter(String sagaId) {
        return sagaStore.findDeadLetter(sagaId).orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "死信不存在: " + sagaId));
    }

    /** 死信行 → snake_case 视图 */
    private Map<String, Object> toView(SagaDeadLetter letter) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("saga_id", letter.getSagaId());
        view.put("doc_code", letter.getDocCode());
        view.put("doc_uuid", letter.getDocUuid());
        view.put("from_status", letter.getFromStatus());
        view.put("to_status", letter.getToStatus());
        view.put("failed_step_seq", letter.getFailedStepSeq());
        view.put("fail_phase", letter.getFailPhase());
        view.put("error_reason", letter.getErrorReason());
        view.put("handle_status", letter.getHandleStatus());
        view.put("handler", letter.getHandler());
        view.put("handle_time", letter.getHandleTime());
        view.put("handle_remark", letter.getHandleRemark());
        view.put("create_time", letter.getCreateTime());
        return view;
    }
}
