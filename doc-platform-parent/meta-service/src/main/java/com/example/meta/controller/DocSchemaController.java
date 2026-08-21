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
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.service.doc.DocSchemaService;
import com.example.meta.web.dto.DocSchemaCreateRequest;
import com.example.meta.web.dto.DocSchemaUpdateRequest;

/**
 * 单据定义管理控制器（DC-01~05，design/09 §2.1 对外路径）。
 *
 * <p>报文口径（prd/11）：请求/响应 snake_case；写操作响应
 * data={doc_code,status,version}，status 为定义状态数值（100/1/0）。</p>
 */
@RestController
@RequestMapping("/api/v1/schema/doc")
public class DocSchemaController {

    private final DocSchemaService docSchemaService;

    public DocSchemaController(DocSchemaService docSchemaService) {
        this.docSchemaService = docSchemaService;
    }

    /** DC-01 创建单据定义（DRAFT，version=0） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DocSchemaCreateRequest request) {
        DocSchemaDefinition definition = docSchemaService.create(request);
        return ApiResponse.ok(writeResult(definition));
    }

    /** DC-02 单据定义列表（status/keyword 过滤 + offset/limit 分页，update_time 倒序） */
    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<Map<String, Object>> views = docSchemaService.list(status, keyword).stream()
                .map(DocSchemaController::toView)
                .collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(views, offset, limit));
    }

    /** DC-02 单据定义详情（未命中 40401） */
    @GetMapping("/{code}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("code") String code) {
        return ApiResponse.ok(toView(docSchemaService.detail(code)));
    }

    /** DC-02 修改单据定义（仅 DRAFT 可修改，40007/40901） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PutMapping("/{code}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("code") String code,
                                                   @Valid @RequestBody DocSchemaUpdateRequest request) {
        DocSchemaDefinition definition = docSchemaService.update(code, request);
        return ApiResponse.ok(writeResult(definition));
    }

    /** DC-02 删除单据定义（仅草稿态，40009） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @DeleteMapping("/{code}")
    public ApiResponse<Void> delete(@PathVariable("code") String code) {
        docSchemaService.delete(code);
        return ApiResponse.ok(null);
    }

    /** DC-03 发布（校验失败 40011+reasons；成功 status=1、version+1、published_at 落值） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping("/{code}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable("code") String code) {
        DocSchemaDefinition definition = docSchemaService.publish(code);
        return ApiResponse.ok(writeResult(definition));
    }

    /** DC-03 停用（仅已发布可停用；status=0） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PostMapping("/{code}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable("code") String code) {
        DocSchemaDefinition definition = docSchemaService.disable(code);
        return ApiResponse.ok(writeResult(definition));
    }

    /** 写操作响应体（doc_code/status/version） */
    private static Map<String, Object> writeResult(DocSchemaDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc_code", definition.getCode());
        result.put("status", (int) definition.getStatus());
        result.put("version", definition.getVersion());
        return result;
    }

    /** 详情/列表视图（snake_case 手工组装，含头属性与状态字典） */
    private static Map<String, Object> toView(DocSchemaDefinition definition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uuid", definition.getUuid());
        view.put("doc_code", definition.getCode());
        view.put("doc_name", definition.getName());
        view.put("doc_type", definition.getDocType());
        view.put("description", definition.getDescription());
        view.put("status", (int) definition.getStatus());
        view.put("version", definition.getVersion());
        view.put("attributes", definition.getAttributes());
        view.put("status_defs", definition.getStatusDefs());
        view.put("creator", definition.getCreator());
        view.put("create_time", definition.getCreateTime());
        view.put("update_time", definition.getUpdateTime());
        view.put("published_at", definition.getPublishedAt());
        return view;
    }
}
