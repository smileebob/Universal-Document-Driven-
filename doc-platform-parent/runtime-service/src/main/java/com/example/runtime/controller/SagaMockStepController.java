package com.example.runtime.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;

/**
 * Saga 步骤 Mock 桩端点（任务清单 3：SAP/APS/通知目标系统的可配置 Mock）。
 *
 * <p>供出口验证「模拟 APS 失败 → SAP 冲正」：tx_config 步骤 actionUrl /
 * compensateUrl 配置为 {@code http://127.0.0.1:8082/api/v1/mock/tx/{system}/action|compensate}。</p>
 *
 * <p>故障注入（两种途径，任一命中即返回 HTTP 500）：</p>
 * <ul>
 *   <li>请求体 {@code {"fail": true}}（单次）；</li>
 *   <li>全局开关 {@code POST /mock/tx/{system}/fail-once}（下一次调用失败后自动复位）。</li>
 * </ul>
 *
 * <p><b>状态码语义</b>：故障注入命中时必须返回 HTTP 500（而非仅 body 内 code），
 * 否则 SagaStepHttpClient 按 2xx 判定成功，编排器不会触发冲正。</p>
 *
 * <p>调用记录保留最近 200 条（含 X-Saga-Id 透传头证据），经
 * {@code GET /mock/tx/{system}/calls} 查询。</p>
 */
@RestController
@RequestMapping("/api/v1/mock/tx")
public class SagaMockStepController {

    /** 调用记录环形上限 */
    private static final int RECORD_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(SagaMockStepController.class);

    /** 各系统下一次调用强制失败开关（故障注入） */
    private final ConcurrentMap<String, Boolean> failOnce = new ConcurrentHashMap<>();

    /** 各系统调用记录 */
    private final ConcurrentMap<String, List<Map<String, Object>>> callRecords = new ConcurrentHashMap<>();

    /**
     * 正向执行桩（action）。
     *
     * @param system 目标系统（sap/aps/notify 等任意标识）
     * @param body   Saga 编排器出站体（含 saga_id/doc_code/step_seq）
     * @param sagaId X-Saga-Id 透传头（记录证据）
     * @return 200 成功；500 故障注入命中
     */
    @PostMapping("/{system}/action")
    public ResponseEntity<ApiResponse<Object>> action(@PathVariable("system") String system,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      @RequestHeader(value = "X-Saga-Id", required = false) String sagaId) {
        return handle(system, "action", body, sagaId);
    }

    /** 冲正桩（compensate） */
    @PostMapping("/{system}/compensate")
    public ResponseEntity<ApiResponse<Object>> compensate(@PathVariable("system") String system,
                                                          @RequestBody(required = false) Map<String, Object> body,
                                                          @RequestHeader(value = "X-Saga-Id", required = false) String sagaId) {
        return handle(system, "compensate", body, sagaId);
    }

    /**
     * 故障注入：指定系统下一次调用强制失败（调用后自动复位）。
     */
    @PostMapping("/{system}/fail-once")
    public ApiResponse<Object> failOnce(@PathVariable("system") String system) {
        failOnce.put(system.toLowerCase(), Boolean.TRUE);
        log.info("[mock-tx] 故障注入已设置: system={} 下一次调用将失败", system);
        return ApiResponse.ok(Map.of("system", system.toLowerCase(), "fail_once", true));
    }

    /** 清除故障注入开关 */
    @DeleteMapping("/{system}/fail-once")
    public ApiResponse<Object> clearFailOnce(@PathVariable("system") String system) {
        failOnce.remove(system.toLowerCase());
        return ApiResponse.ok(Map.of("system", system.toLowerCase(), "fail_once", false));
    }

    /** 查询调用记录（消费/执行证据） */
    @org.springframework.web.bind.annotation.GetMapping("/{system}/calls")
    public ApiResponse<Object> calls(@PathVariable("system") String system) {
        List<Map<String, Object>> records = callRecords.getOrDefault(
                system.toLowerCase(), List.of());
        synchronized (records) {
            return ApiResponse.ok(Map.of("system", system.toLowerCase(),
                    "count", records.size(), "calls", new ArrayList<>(records)));
        }
    }

    /** 清空调用记录 */
    @DeleteMapping("/{system}/calls")
    public ApiResponse<Object> clearCalls(@PathVariable("system") String system) {
        callRecords.remove(system.toLowerCase());
        return ApiResponse.ok(Map.of("system", system.toLowerCase(), "cleared", true));
    }

    // ==================== 内部 ====================

    private ResponseEntity<ApiResponse<Object>> handle(String system, String phase,
                                                       Map<String, Object> body, String sagaId) {
        String key = system.toLowerCase();
        boolean bodyFail = body != null && Boolean.TRUE.equals(body.get("fail"));
        boolean injected = Boolean.TRUE.equals(failOnce.remove(key));
        boolean shouldFail = bodyFail || injected;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("phase", phase);
        record.put("saga_id_header", sagaId);
        record.put("body", body == null ? Map.of() : body);
        record.put("result", shouldFail ? "fail" : "ok");
        record.put("ts", System.currentTimeMillis());
        List<Map<String, Object>> records = callRecords.computeIfAbsent(key,
                k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (records) {
            records.add(record);
            while (records.size() > RECORD_LIMIT) {
                records.remove(0);
            }
        }
        log.info("[mock-tx] system={}, phase={}, sagaId={}, result={}",
                key, phase, sagaId, shouldFail ? "fail" : "ok");
        if (shouldFail) {
            // 必须返回 HTTP 500：编排器按非 2xx 判定步骤失败并转冲正
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(50000, "mock 故障注入: system=" + key + ", phase=" + phase));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("system", key);
        data.put("phase", phase);
        data.put("accepted", true);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}
