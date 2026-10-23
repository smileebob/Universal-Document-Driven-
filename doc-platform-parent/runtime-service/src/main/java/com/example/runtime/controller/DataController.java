package com.example.runtime.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.model.ApiResponse;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.DataService;
import com.example.runtime.service.IdempotencyService;

/**
 * 实体数据 CRUD 控制器（RD-01~04，design/09 §2.1）。
 *
 * <p>路由：POST /api/v1/data/{entityCode}（新增）、GET 详情、PUT 更新、
 * DELETE 逻辑删；写接口经 {@link IdempotencyService} 承载 X-Idempotency-Key
 * 幂等语义（design/09 §3.2），审计为成功后异步旁路（{@link AuditService}，RD-12）。</p>
 *
 * <p>操作人口径（GW-04）：X-User-Id 为权威值；来源 IP 优先取网关写入的
 * X-Real-IP，其次取 X-Forwarded-For 首段（可信边界见 AuthGlobalFilter）。</p>
 */
@RestController
@RequestMapping("/api/v1/data")
public class DataController {

    private final DataService dataService;
    private final IdempotencyService idempotencyService;

    public DataController(DataService dataService, IdempotencyService idempotencyService) {
        this.dataService = dataService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 新增实体数据（RD-01）。
     *
     * @param entityCode     实体编码
     * @param body           {@code {"attributes": {...}}}
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id（权威操作人）
     * @param userDept       X-User-Dept（创建部门）
     * @param forwardedFor   X-Forwarded-For（来源 IP，取首段）
     * @param traceId        X-Trace-Id
     * @return {@code {uuid, status}}
     */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping("/{entityCode}")
    public ApiResponse<Object> create(@PathVariable("entityCode") String entityCode,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @RequestHeader(value = "X-User-Dept", required = false) String userDept,
                                      @RequestHeader(value = "X-Real-IP", required = false) String realIp,
                                      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        Map<String, Object> attributes = attributesOf(body);
        Object result = idempotencyService.execute(idempotencyKey, "POST /api/v1/data/" + entityCode,
                () -> dataService.create(entityCode, attributes, operator(userId), userDept,
                        clientIp(realIp, forwardedFor), traceId));
        return ApiResponse.ok(result);
    }

    /**
     * 数据详情（RD-04，主键点查 + REF 展示值展开）。
     *
     * @param entityCode 实体编码
     * @param uuid       数据唯一标识
     * @return 详情投影
     */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN,
            PlatformRoles.BUSINESS_USER})
    @GetMapping("/{entityCode}/{uuid}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("entityCode") String entityCode,
                                                   @PathVariable("uuid") String uuid) {
        return ApiResponse.ok(dataService.detail(entityCode, uuid));
    }

    /**
     * 修改实体数据（RD-02，version 乐观锁必填）。
     *
     * @param entityCode     实体编码
     * @param uuid           数据唯一标识
     * @param body           {@code {"attributes": {...}, "version": N}}
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id
     * @param forwardedFor   X-Forwarded-For
     * @param traceId        X-Trace-Id
     * @return {@code {uuid, version, status}}
     */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PutMapping("/{entityCode}/{uuid}")
    public ApiResponse<Object> update(@PathVariable("entityCode") String entityCode,
                                      @PathVariable("uuid") String uuid,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @RequestHeader(value = "X-Real-IP", required = false) String realIp,
                                      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        Map<String, Object> attributes = attributesOf(body);
        long expectVersion = requireVersion(body);
        Object result = idempotencyService.execute(idempotencyKey,
                "PUT /api/v1/data/" + entityCode + "/" + uuid,
                () -> dataService.update(entityCode, uuid, attributes, expectVersion,
                        operator(userId), clientIp(realIp, forwardedFor), traceId));
        return ApiResponse.ok(result);
    }

    /**
     * 逻辑删除（RD-03，引用保护 + status 置 0；唯一占位保留不释放）。
     *
     * @param entityCode     实体编码
     * @param uuid           数据唯一标识
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id
     * @param forwardedFor   X-Forwarded-For
     * @param traceId        X-Trace-Id
     * @return 空数据成功响应
     */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @DeleteMapping("/{entityCode}/{uuid}")
    public ApiResponse<Object> delete(@PathVariable("entityCode") String entityCode,
                                      @PathVariable("uuid") String uuid,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @RequestHeader(value = "X-Real-IP", required = false) String realIp,
                                      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        Object result = idempotencyService.execute(idempotencyKey,
                "DELETE /api/v1/data/" + entityCode + "/" + uuid,
                () -> {
                    dataService.delete(entityCode, uuid, operator(userId),
                            clientIp(realIp, forwardedFor), traceId);
                    return Map.of("uuid", uuid, "status", 0);
                });
        return ApiResponse.ok(result);
    }

    // ==================== 请求解析 ====================

    /** 提取 attributes 载荷（缺省空对象） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> attributesOf(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        Object attributes = body.get("attributes");
        if (attributes == null) {
            return Map.of();
        }
        if (!(attributes instanceof Map)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "attributes 须为 JSON 对象");
        }
        return (Map<String, Object>) attributes;
    }

    /** version 乐观锁必填校验（RD-02 / TS-04） */
    private long requireVersion(Map<String, Object> body) {
        Object version = body == null ? null : body.get("version");
        if (!(version instanceof Number number)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "version 不可为空（乐观锁必填）");
        }
        return number.longValue();
    }

    /** 操作人：X-User-Id 权威值，缺省 anonymous */
    private String operator(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    /** 来源 IP：网关可信头优先（X-Real-IP），HTTP 头缺失时退回 XFF 首段 */
    private String clientIp(String realIp, String forwardedFor) {
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        return forwardedFor.split(",")[0].trim();
    }
}
