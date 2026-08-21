package com.example.runtime.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.model.ApiResponse;
import com.example.runtime.service.IdempotencyService;
import com.example.runtime.service.transition.TransitionService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 单据状态流转控制器（POST /api/v1/data/doc/{docCode}/{uuid}/transition，
 * design/09 §2.1 / §5.2）。
 *
 * <p>幂等（⑦）：X-Idempotency-Key 经 {@link IdempotencyService} 在分布式锁外
 * 占位，重放直接回放首次结果；操作人口径 GW-04（X-User-Id 权威）。
 * 幂等键同时透传流转服务用于 Saga 幂等派生（阶段三）。</p>
 */
@RestController
@RequestMapping("/api/v1/data/doc")
public class TransitionController {

    private final TransitionService transitionService;
    private final IdempotencyService idempotencyService;

    /** 流转成功/失败计数（design/11 §4.2：ddp_transition_total → 流转成功率告警） */
    private Counter successCounter;
    private Counter failedCounter;

    public TransitionController(TransitionService transitionService,
                                IdempotencyService idempotencyService) {
        this.transitionService = transitionService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 状态流转（八步链路入口）。
     *
     * @param docCode        单据编码
     * @param uuid           单据实例标识
     * @param body           {@code {"target_status": 10, "event": "submit"}}
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id（权威操作人）
     * @return 成功 {@code {uuid,doc_code,from_status,to_status,version,history_seq,saga_triggered,saga_id?}}；
     *         42201 {@code data={failed_rules,saga_executed}}；
     *         42202 {@code data={saga_id,saga_status}}（COMPENSATED/DEAD）
     */
    @PostMapping("/{docCode}/{uuid}/transition")
    public ApiResponse<Object> transition(@PathVariable("docCode") String docCode,
                                          @PathVariable("uuid") String uuid,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestHeader(value = "X-User-Id", required = false) String userId) {
        long targetStatus = requireTargetStatus(body);
        String event = body == null || body.get("event") == null
                ? "transition" : String.valueOf(body.get("event"));
        String operator = userId == null || userId.isBlank() ? "anonymous" : userId;
        String apiPath = "POST /api/v1/data/doc/" + docCode + "/" + uuid + "/transition";
        Object result;
        try {
            result = idempotencyService.execute(idempotencyKey, apiPath,
                    () -> transitionService.transition(docCode, uuid, targetStatus, event,
                            operator, idempotencyKey));
        } catch (RuntimeException e) {
            increment(failedCounter);
            throw e;
        }
        increment(successCounter);
        return ApiResponse.ok(result);
    }

    /**
     * 绑定流转计数（{@code ddp_transition_total{result=success|failed}}，
     * design/11 §4.2 流转成功率告警联动）。Setter 注入（required=false）。
     *
     * @param registry Micrometer 注册表
     */
    @Autowired(required = false)
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        this.successCounter = Counter.builder("ddp_transition_total")
                .tag("result", "success").register(registry);
        this.failedCounter = Counter.builder("ddp_transition_total")
                .tag("result", "failed").register(registry);
    }

    /** 计数器空安全自增（未绑定 metrics 时 NOOP） */
    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /** target_status 必填校验（40001） */
    private long requireTargetStatus(Map<String, Object> body) {
        Object target = body == null ? null : body.get("target_status");
        if (!(target instanceof Number number)) {
            throw new BizException(ErrorCode.REQUIRED_MISSING, "target_status 不可为空");
        }
        return number.longValue();
    }
}
