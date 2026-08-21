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
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.service.doc.RuleLibService;
import com.example.meta.web.dto.RuleCreateRequest;
import com.example.meta.web.dto.RuleTestRequest;
import com.example.meta.web.dto.RuleUpdateRequest;

/**
 * 规则库管理控制器（RE-01/02：CRUD + 在线测试）。
 */
@RestController
@RequestMapping("/api/v1/rule")
public class RuleController {

    private final RuleLibService ruleLibService;

    public RuleController(RuleLibService ruleLibService) {
        this.ruleLibService = ruleLibService;
    }

    /** RE-01 创建规则（uuid 服务端生成，version=0） */
    @RequireRoles(PlatformRoles.PLATFORM_ADMIN)
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody RuleCreateRequest request) {
        RuleLibEntry entry = ruleLibService.create(request);
        return ApiResponse.ok(writeResult(entry));
    }

    /** RE-02 规则列表（keyword/enabled 过滤 + offset/limit 分页，update_time 倒序） */
    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<Map<String, Object>> views = ruleLibService.list(keyword, enabled).stream()
                .map(RuleController::toView)
                .collect(Collectors.toList());
        return ApiResponse.ok(PageResult.of(views, offset, limit));
    }

    /** RE-02 规则详情（未命中 40401） */
    @GetMapping("/{uuid}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("uuid") String uuid) {
        return ApiResponse.ok(toView(ruleLibService.detail(uuid)));
    }

    /** RE-02 修改规则（version CAS，保存即时失效） */
    @RequireRoles(PlatformRoles.PLATFORM_ADMIN)
    @PutMapping("/{uuid}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("uuid") String uuid,
                                                   @Valid @RequestBody RuleUpdateRequest request) {
        RuleLibEntry entry = ruleLibService.update(uuid, request);
        return ApiResponse.ok(writeResult(entry));
    }

    /** RE-02 删除规则（被单据绑定 40010+bound_docs） */
    @RequireRoles(PlatformRoles.PLATFORM_ADMIN)
    @DeleteMapping("/{uuid}")
    public ApiResponse<Void> delete(@PathVariable("uuid") String uuid) {
        ruleLibService.delete(uuid);
        return ApiResponse.ok(null);
    }

    /** RE-02 在线测试（与运行时同一执行器；返回 passed/skipped/reason） */
    @RequireRoles(PlatformRoles.PLATFORM_ADMIN)
    @PostMapping("/{uuid}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable("uuid") String uuid,
                                                 @RequestBody(required = false) RuleTestRequest request) {
        return ApiResponse.ok(ruleLibService.test(uuid, request));
    }

    /** 写操作响应体（rule_uuid/version） */
    private static Map<String, Object> writeResult(RuleLibEntry entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_uuid", entry.getUuid());
        result.put("version", entry.getVersion());
        return result;
    }

    /** 详情/列表视图（snake_case 手工组装） */
    private static Map<String, Object> toView(RuleLibEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rule_uuid", entry.getUuid());
        view.put("rule_name", entry.getRuleName());
        view.put("rule_type", entry.getRuleType());
        view.put("expression", entry.getExpression());
        view.put("interface_code", entry.getInterfaceCode());
        view.put("enabled", entry.isEnabled());
        view.put("description", entry.getDescription());
        view.put("version", entry.getVersion());
        view.put("creator", entry.getCreator());
        view.put("create_time", entry.getCreateTime());
        view.put("update_time", entry.getUpdateTime());
        return view;
    }
}
