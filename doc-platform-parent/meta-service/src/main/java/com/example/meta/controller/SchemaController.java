package com.example.meta.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.common.model.PageResult;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.service.schema.SchemaService;
import com.example.meta.service.schema.SchemaViewMapper;
import com.example.meta.web.dto.EntityCreateRequest;
import com.example.meta.web.dto.EntitySchemaView;
import com.example.meta.web.dto.EntityUpdateRequest;

/**
 * 实体定义管理控制器（EC-01~04，design/09 §2.1 对外路径）。
 *
 * <p>报文口径（prd/11 §3.1）：请求/响应 snake_case；写操作响应
 * data={entity_code,status,version}，status 为存储数值（100/1/0）。</p>
 */
@RestController
@RequestMapping("/api/v1/schema")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    /** EC-01 创建实体定义（DRAFT，version=0） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping("/entity")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody EntityCreateRequest request) {
        EntitySchema schema = schemaService.create(request);
        return ApiResponse.ok(writeResult(schema));
    }

    /** EC-02 实体定义列表（prd/11 §一：data={total,items}；status/keyword/entity_type 过滤 + offset/limit 分页，update_time 倒序） */
    @GetMapping("/entity")
    public ApiResponse<PageResult<EntitySchemaView>> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "entity_type", required = false) String entityType,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<EntitySchemaView> views = schemaService.list(status, keyword).stream()
                .filter(schema -> entityType == null || entityType.isBlank()
                        || entityType.equals(schema.getEntityType()))
                .map(SchemaViewMapper::toView)
                .collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(views, offset, limit));
    }

    /** EC-02 实体定义详情（未命中 40401） */
    @GetMapping("/entity/{code}")
    public ApiResponse<EntitySchemaView> detail(@PathVariable("code") String code) {
        return ApiResponse.ok(SchemaViewMapper.toView(schemaService.detail(code)));
    }

    /** EC-02 修改实体定义（仅 DRAFT 可修改，40007/40901） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PutMapping("/entity/{code}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("code") String code,
                                                   @Valid @RequestBody EntityUpdateRequest request) {
        EntitySchema schema = schemaService.update(code, request);
        return ApiResponse.ok(writeResult(schema));
    }

    /** EC-02 删除实体定义（仅草稿态，40009） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @DeleteMapping("/entity/{code}")
    public ApiResponse<Void> delete(@PathVariable("code") String code) {
        schemaService.delete(code);
        return ApiResponse.ok(null);
    }

    /** EC-03 发布（校验失败 40011+reasons；成功 status=1、version+1、published_at 落值） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping("/entity/{code}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable("code") String code) {
        EntitySchema schema = schemaService.publish(code);
        return ApiResponse.ok(writeResult(schema));
    }

    /** EC-03 停用（仅已发布可停用；级联取消菜单挂载；status=0） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping("/entity/{code}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable("code") String code) {
        EntitySchema schema = schemaService.disable(code);
        return ApiResponse.ok(writeResult(schema));
    }

    /** 写操作响应体（prd/11 §3.1：entity_code/status/version） */
    private static Map<String, Object> writeResult(EntitySchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity_code", schema.getCode());
        result.put("status", (int) schema.getStatus().getStorageValue());
        result.put("version", schema.getVersion());
        return result;
    }
}
