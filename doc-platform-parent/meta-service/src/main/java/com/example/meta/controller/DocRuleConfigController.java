package com.example.meta.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.service.doc.DocRuleConfigService;
import com.example.meta.web.dto.DocRuleConfigRequest;

/**
 * 单据规则绑定控制器（RE-05，保存即生效）。
 */
@RestController
@RequestMapping("/api/v1/schema/doc")
public class DocRuleConfigController {

    private final DocRuleConfigService docRuleConfigService;

    public DocRuleConfigController(DocRuleConfigService docRuleConfigService) {
        this.docRuleConfigService = docRuleConfigService;
    }

    /** RE-05 保存规则绑定（引用规则不存在 40004+missing_rules） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PutMapping("/{code}/rules")
    public ApiResponse<Map<String, Object>> save(@PathVariable("code") String code,
                                                 @Valid @RequestBody DocRuleConfigRequest request) {
        return ApiResponse.ok(docRuleConfigService.save(code, request));
    }

    /** RE-05 查询规则绑定（未配置 40401） */
    @GetMapping("/{code}/rules")
    public ApiResponse<Map<String, Object>> get(@PathVariable("code") String code) {
        DocRuleConfig config = docRuleConfigService.get(code);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("doc_code", config.getDocCode());
        view.put("create_rules", config.getCreateRules());
        view.put("status_rules", config.getStatusRules());
        view.put("version", config.getVersion());
        view.put("update_time", config.getUpdateTime());
        return ApiResponse.ok(view);
    }
}
