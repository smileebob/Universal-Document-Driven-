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
import com.example.meta.domain.doc.StateMachineConfig;
import com.example.meta.service.doc.StateMachineService;
import com.example.meta.web.dto.StateMachineRequest;

/**
 * 单据状态机配置控制器（SM-01/02，保存即生效）。
 */
@RestController
@RequestMapping("/api/v1/schema/doc")
public class StateMachineController {

    private final StateMachineService stateMachineService;

    public StateMachineController(StateMachineService stateMachineService) {
        this.stateMachineService = stateMachineService;
    }

    /** SM-01 保存状态机（预校验硬错误 40011+reasons；不可达状态返回 warnings） */
    @RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
    @PutMapping("/{code}/state-machine")
    public ApiResponse<Map<String, Object>> save(@PathVariable("code") String code,
                                                 @Valid @RequestBody StateMachineRequest request) {
        return ApiResponse.ok(stateMachineService.save(code, request));
    }

    /** SM-02 查询状态机（未配置 40401） */
    @GetMapping("/{code}/state-machine")
    public ApiResponse<Map<String, Object>> get(@PathVariable("code") String code) {
        StateMachineConfig config = stateMachineService.get(code);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("doc_code", config.getDocCode());
        view.put("states", config.getStates());
        view.put("transitions", config.getTransitions());
        view.put("version", config.getVersion());
        view.put("update_time", config.getUpdateTime());
        return ApiResponse.ok(view);
    }
}
