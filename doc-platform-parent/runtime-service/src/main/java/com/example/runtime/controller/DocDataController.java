package com.example.runtime.controller;

import java.util.List;
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
import com.example.runtime.service.IdempotencyService;
import com.example.runtime.service.data.DocDataService;

/**
 * 单据数据 CRUD 控制器（RD-05~08，design/09 §2.1）。
 *
 * <p>路由：POST /api/v1/data/doc/{docCode}（创建）、GET/PUT/DELETE
 * /api/v1/data/doc/{docCode}/{uuid}；写接口经 {@link IdempotencyService}
 * 承载 X-Idempotency-Key；流转入口见 TransitionController。
 * 操作人口径 GW-04：X-User-Id 权威。</p>
 */
@RestController
@RequestMapping("/api/v1/data/doc")
public class DocDataController {

    private final DocDataService docDataService;
    private final IdempotencyService idempotencyService;

    public DocDataController(DocDataService docDataService,
                             IdempotencyService idempotencyService) {
        this.docDataService = docDataService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 创建单据（初始状态 100 暂存，create_rules 规则校验）。
     *
     * @param docCode        单据编码
     * @param body           {@code {"attributes": {...}}}
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id（权威操作人）
     * @param userDept       X-User-Dept（创建部门）
     * @return {@code {uuid, doc_code, current_status, version}}
     */
    @PostMapping("/{docCode}")
    public ApiResponse<Object> create(@PathVariable("docCode") String docCode,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @RequestHeader(value = "X-User-Dept", required = false) String userDept) {
        Map<String, Object> attributes = attributesOf(body);
        Object result = idempotencyService.execute(idempotencyKey,
                "POST /api/v1/data/doc/" + docCode,
                () -> docDataService.create(docCode, attributes, operator(userId), userDept));
        return ApiResponse.ok(result);
    }

    /**
     * 单据详情（主键点查 + 状态名投影）。
     *
     * @param docCode 单据编码
     * @param uuid    实例标识
     * @return snake_case 详情视图
     */
    @GetMapping("/{docCode}/{uuid}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("docCode") String docCode,
                                                   @PathVariable("uuid") String uuid) {
        return ApiResponse.ok(docDataService.detail(docCode, uuid));
    }

    /**
     * 单据状态变更历史（doc_data_history 伴生表，seq 升序，RD-08 / 前端状态时间线）。
     *
     * @param docCode 单据编码
     * @param uuid    实例标识
     * @return snake_case 记录数组（seq/from_status/to_status/operation/operator/
     *         operate_time/version_after + 状态名投影）
     */
    @GetMapping("/{docCode}/{uuid}/history")
    public ApiResponse<List<Map<String, Object>>> history(@PathVariable("docCode") String docCode,
                                                          @PathVariable("uuid") String uuid) {
        return ApiResponse.ok(docDataService.history(docCode, uuid));
    }

    /**
     * 修改单据（仅可编辑状态；version 乐观锁必填）。
     *
     * @param docCode        单据编码
     * @param uuid           实例标识
     * @param body           {@code {"attributes": {...}, "version": N}}
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id
     * @return {@code {uuid, doc_code, current_status, version}}
     */
    @PutMapping("/{docCode}/{uuid}")
    public ApiResponse<Object> update(@PathVariable("docCode") String docCode,
                                      @PathVariable("uuid") String uuid,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId) {
        Map<String, Object> attributes = attributesOf(body);
        long expectVersion = requireVersion(body);
        Object result = idempotencyService.execute(idempotencyKey,
                "PUT /api/v1/data/doc/" + docCode + "/" + uuid,
                () -> docDataService.update(docCode, uuid, attributes, expectVersion, operator(userId)));
        return ApiResponse.ok(result);
    }

    /**
     * 逻辑删除（仅暂存态可删，40009）。
     *
     * @param docCode        单据编码
     * @param uuid           实例标识
     * @param idempotencyKey X-Idempotency-Key（可选）
     * @param userId         X-User-Id
     * @return {@code {uuid, deleted}}
     */
    @DeleteMapping("/{docCode}/{uuid}")
    public ApiResponse<Object> delete(@PathVariable("docCode") String docCode,
                                      @PathVariable("uuid") String uuid,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId) {
        Object result = idempotencyService.execute(idempotencyKey,
                "DELETE /api/v1/data/doc/" + docCode + "/" + uuid,
                () -> docDataService.delete(docCode, uuid, operator(userId)));
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

    /** version 乐观锁必填校验（RD-06 / TS-04） */
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
}
