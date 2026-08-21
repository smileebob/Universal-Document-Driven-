package com.example.meta.controller;

import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.model.ApiResponse;
import com.example.meta.api.dto.TxConfigDTO;
import com.example.meta.service.txconfig.TxConfigService;

/**
 * 事务步骤配置控制器（PUT/GET /api/v1/schema/doc/{code}/tx-config，
 * TX-01 / design/09 §2.1，保存即生效）。
 *
 * <p>前端页面（任务 #17）契约：</p>
 * <ul>
 *   <li>PUT body：{@code {transitions:[{from_status,to_status,
 *       steps:[{seq,name,action_url,compensate_url,timeout_ms,retry}]}]}}</li>
 *   <li>GET 响应：{@code data={doc_code,transitions[...],update_time}}；
 *       未配置 HTTP 404 + code 40401。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/schema/doc")
public class TxConfigController {

    private final TxConfigService txConfigService;

    public TxConfigController(TxConfigService txConfigService) {
        this.txConfigService = txConfigService;
    }

    /**
     * 保存事务步骤配置（upsert，保存即时失效）。
     *
     * @param docCode 单据编码
     * @param dto     配置请求
     * @return {@code {doc_code, update_time}}
     */
    @RequireRoles(PlatformRoles.PLATFORM_ADMIN)
    @PutMapping("/{docCode}/tx-config")
    public ApiResponse<Map<String, Object>> save(@PathVariable("docCode") String docCode,
                                                 @RequestBody(required = false) TxConfigDTO dto) {
        return ApiResponse.ok(txConfigService.save(docCode, dto));
    }

    /**
     * 查询事务步骤配置（未配置 40401）。
     *
     * @param docCode 单据编码
     * @return 配置 DTO
     */
    @GetMapping("/{docCode}/tx-config")
    public ApiResponse<TxConfigDTO> get(@PathVariable("docCode") String docCode) {
        TxConfigDTO dto = txConfigService.getDto(docCode);
        if (dto == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "事务步骤配置未配置: " + docCode);
        }
        return ApiResponse.ok(dto);
    }
}
