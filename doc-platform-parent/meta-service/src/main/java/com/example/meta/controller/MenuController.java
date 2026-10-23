package com.example.meta.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.meta.service.schema.MenuService;
import com.example.meta.web.dto.MenuMountRequest;
import com.example.meta.web.dto.MenuMountView;
import com.example.meta.web.dto.MenuMountedView;

/**
 * 菜单挂载控制器（EC-05，design/09 §2.1 对外路径）。
 *
 * <p>PUT /api/v1/schema/{type}/{code}/menu：挂载配置（enabled=false 即取消挂载）；
 * GET /api/v1/schema/{type}/{code}/menu：读取单个目标配置（编辑器回填）；
 * GET /api/v1/menu/mounted：已挂载视图（分组 + 排序 + 全局 schema_version）。</p>
 */
@RestController
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /** 挂载/更新菜单（幂等 PUT） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PutMapping("/api/v1/schema/{type}/{code}/menu")
    public ApiResponse<Void> mount(@PathVariable("type") String targetType,
                                   @PathVariable("code") String targetCode,
                                   @Valid @RequestBody MenuMountRequest request) {
        menuService.mount(targetType, targetCode, request);
        return ApiResponse.ok(null);
    }

    /** 读取单个目标挂载配置（未挂载返回 enabled=false 空视图） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @GetMapping("/api/v1/schema/{type}/{code}/menu")
    public ApiResponse<MenuMountView> config(@PathVariable("type") String targetType,
                                             @PathVariable("code") String targetCode) {
        return ApiResponse.ok(menuService.getMount(targetType, targetCode));
    }

    /** 已挂载菜单视图（仅已挂载且已发布，携带全局 schema_version） */
    @GetMapping("/api/v1/menu/mounted")
    public ApiResponse<MenuMountedView> mounted() {
        return ApiResponse.ok(menuService.mounted());
    }
}
