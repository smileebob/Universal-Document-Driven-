package com.example.runtime.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.runtime.infrastructure.reconcile.UniqueOrphanReconcileTask;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运维对账控制器（unique_index 孤儿占位手动对账，design/03 §5.6）。
 *
 * <p>手动触发不受 {@code runtime.reconcile.unique-orphan.enabled} 定时开关限制；
 * 并发执行时返回 40901。操作人口径 GW-04：X-User-Id 权威。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/reconcile")
@RequireRoles(PlatformRoles.PLATFORM_ADMIN) // 运营接口限平台管理员（design/11 §六-3）
public class ReconcileAdminController {

    private static final Logger log = LoggerFactory.getLogger(ReconcileAdminController.class);

    private final UniqueOrphanReconcileTask reconcileTask;

    public ReconcileAdminController(UniqueOrphanReconcileTask reconcileTask) {
        this.reconcileTask = reconcileTask;
    }

    /**
     * 手动触发 unique_index 孤儿对账（全量扫描 + 孤儿释放 + 对账审计）。
     *
     * @param userId X-User-Id（操作人，审计追踪用）
     * @return 对账摘要 {scanned, released, skipped_fresh, skipped_active, errors}
     */
    @PostMapping("/unique-orphan")
    public ApiResponse<Map<String, Object>> reconcileUniqueOrphan(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String operator = userId == null || userId.isBlank() ? "anonymous" : userId;
        log.info("[reconcile] 手动触发 unique_index 孤儿对账: operator={}", operator);
        return ApiResponse.ok(reconcileTask.triggerManual());
    }
}
