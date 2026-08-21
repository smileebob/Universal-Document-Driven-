package com.example.meta.controller;

import java.util.List;

import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.api.dto.TxConfigDTO;
import com.example.meta.service.doc.DocRuleConfigService;
import com.example.meta.service.doc.DocSchemaService;
import com.example.meta.service.doc.RuleLibService;
import com.example.meta.service.doc.StateMachineService;
import com.example.meta.service.schema.SchemaService;
import com.example.meta.service.txconfig.TxConfigService;

/**
 * 配置域内部契约控制器：承接 /api/v1/internal/schema/**（design/09 §2.1）。
 *
 * <p>实现 {@link MetaSchemaClient} 契约接口，保证服务端路径与 Feign 契约严格一致；
 * 该前缀不配置网关路由，仅内网服务间 Feign 直达（A-07）。</p>
 *
 * <p>tx_config（阶段三落地）：未配置保持 HTTP 200 + code 40401 形态
 * （MetaApiMvcTest 既有契约），runtime 防腐层映射为「无 Saga 编排」。</p>
 */
@RestController
public class InternalSchemaController implements MetaSchemaClient {

    /** tx_config 未命中语义错误码（prd/12 §三） */
    private static final int CODE_NOT_FOUND = 40401;

    private final SchemaService schemaService;
    private final DocSchemaService docSchemaService;
    private final StateMachineService stateMachineService;
    private final DocRuleConfigService docRuleConfigService;
    private final RuleLibService ruleLibService;
    private final TxConfigService txConfigService;

    public InternalSchemaController(SchemaService schemaService,
                                    DocSchemaService docSchemaService,
                                    StateMachineService stateMachineService,
                                    DocRuleConfigService docRuleConfigService,
                                    RuleLibService ruleLibService,
                                    TxConfigService txConfigService) {
        this.schemaService = schemaService;
        this.docSchemaService = docSchemaService;
        this.stateMachineService = stateMachineService;
        this.docRuleConfigService = docRuleConfigService;
        this.ruleLibService = ruleLibService;
        this.txConfigService = txConfigService;
    }

    @Override
    public ApiResponse<DocSchemaDTO> getDocSchema(String docCode) {
        // 仅已发布可见；缓存回源（CacheDomains.DOC_SCHEMA）；未命中由服务层抛 40401
        return ApiResponse.ok(docSchemaService.getPublishedDto(docCode));
    }

    @Override
    public ApiResponse<List<DocSchemaDTO>> listPublishedDocSchemas() {
        return ApiResponse.ok(docSchemaService.listPublishedDtos());
    }

    @Override
    public ApiResponse<StateMachineDTO> getStateMachine(String docCode) {
        // 未配置由服务层抛 40401，runtime 侧映射为空流转表（任何流转 40003）
        return ApiResponse.ok(stateMachineService.getPublishedDto(docCode));
    }

    @Override
    public ApiResponse<DocRuleConfigDTO> getDocRuleConfig(String docCode) {
        // 未配置由服务层抛 40401，runtime 侧映射为空绑定（不执行规则校验）
        return ApiResponse.ok(docRuleConfigService.getPublishedDto(docCode));
    }

    @Override
    public ApiResponse<TxConfigDTO> getTxConfig(String docCode) {
        // 阶段三：tx_config 事务步骤配置；未配置 HTTP 200 + code 40401（既有契约口径）
        TxConfigDTO dto = txConfigService.getDto(docCode);
        if (dto == null) {
            return ApiResponse.fail(CODE_NOT_FOUND, "事务步骤配置未配置: " + docCode);
        }
        return ApiResponse.ok(dto);
    }

    @Override
    public ApiResponse<RuleLibDTO> getRuleLib(String uuid) {
        // 停用规则同样可见（执行器按 skip 语义处理）；未命中由服务层抛 40401
        return ApiResponse.ok(ruleLibService.getPublishedDto(uuid));
    }

    @Override
    public ApiResponse<EntitySchemaDTO> getEntitySchema(String code) {
        // 缓存回源（L2 → L1 → 重建锁）；未发布/不存在由 SchemaService 抛 40401
        return ApiResponse.ok(schemaService.getPublishedDto(code));
    }

    @Override
    public ApiResponse<List<EntitySchemaDTO>> listPublishedEntitySchemas() {
        return ApiResponse.ok(schemaService.listPublishedDtos());
    }
}
