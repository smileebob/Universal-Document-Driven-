package com.example.runtime.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;
import com.example.runtime.service.event.DocOperationProjector;

/**
 * 操作日志运维接口：按单据/时间窗从持久日志重放 history/outbox/audit。
 *
 * <p>重放只读不可变日志载荷并幂等重写投影，不修改日志与业务主行；
 * 用于历史/审计缺失修复与投影验证。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/doc-operations")
@RequireRoles(PlatformRoles.PLATFORM_ADMIN)
public class DocOperationAdminController {

    private static final Logger log = LoggerFactory.getLogger(DocOperationAdminController.class);

    private final DocOperationProjector projector;

    public DocOperationAdminController(DocOperationProjector projector) {
        this.projector = projector;
    }

    /**
     * 重放指定单据时间窗的操作日志投影。
     *
     * @param docCode 单据编码
     * @param uuid    单据实例
     * @param from    起始时间（毫秒，可空=0）
     * @param to      结束时间（毫秒，可空=当前）
     * @param userId  操作人
     * @return 重放条数
     */
    @PostMapping("/replay")
    public ApiResponse<Map<String, Object>> replay(
            @RequestParam("doc_code") String docCode,
            @RequestParam("uuid") String uuid,
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        long fromTime = from == null ? 0L : from;
        long toTime = to == null ? System.currentTimeMillis() : to;
        int replayed = projector.replay(docCode, uuid, fromTime, toTime);
        log.info("[doc-operation-admin] 手动重放: {}/{}, from={}, to={}, count={}, operator={}",
                docCode, uuid, fromTime, toTime, replayed,
                userId == null || userId.isBlank() ? "anonymous" : userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_code", docCode);
        data.put("uuid", uuid);
        data.put("replayed", replayed);
        return ApiResponse.ok(data);
    }
}
