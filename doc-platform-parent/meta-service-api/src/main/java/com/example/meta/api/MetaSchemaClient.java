package com.example.meta.api;

import java.util.List;

import com.example.common.model.ApiResponse;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.api.dto.TxConfigDTO;
import com.example.meta.api.fallback.MetaSchemaClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 配置域（meta-service）schema 查询 Feign 契约（A-04）。
 *
 * <p>调用方：runtime-service（校验链取 schema、状态流转取状态机/规则绑定/事务配置、ref 展开）；
 * 接口前缀 /api/v1/internal/schema/** 不出网关，仅内网服务间直达（design/08 §2.1 步骤 7）。</p>
 *
 * <p>超时沿用全局 connect 3s / read 5s（P-02）；FallbackFactory 全配（P-04），
 * 降级返回明确错误码（42201/50000 语义由调用方裁决）；仅 GET 幂等接口允许重试且 ≤ 2 次（P-05）。</p>
 */
@FeignClient(name = "meta-service", contextId = "metaSchemaClient",
        fallbackFactory = MetaSchemaClientFallbackFactory.class)
public interface MetaSchemaClient {

    /**
     * 查询单据定义（doc_schema，仅已发布可见；未发布/不存在返回 40401）。
     *
     * @param docCode 单据编码
     * @return 单据定义（含 attributes 头属性与 status_defs 状态字典）
     */
    @GetMapping("/api/v1/internal/schema/doc/{code}")
    ApiResponse<DocSchemaDTO> getDocSchema(@PathVariable("code") String docCode);

    /**
     * 全量拉取已发布单据定义（runtime-service 启动预热，行数百级一次拉齐）。
     *
     * @return 已发布单据定义列表（不含草稿/停用）
     */
    @GetMapping("/api/v1/internal/schema/doc/published")
    ApiResponse<List<DocSchemaDTO>> listPublishedDocSchemas();

    /**
     * 查询单据状态机配置（state_machine）。
     *
     * @param docCode 单据编码
     * @return 状态与流转路径定义
     */
    @GetMapping("/api/v1/internal/schema/doc/{code}/state-machine")
    ApiResponse<StateMachineDTO> getStateMachine(@PathVariable("code") String docCode);

    /**
     * 查询单据规则绑定配置（doc_rule_config）。
     *
     * @param docCode 单据编码
     * @return 各时机（create_rules / status_rules）的规则绑定
     */
    @GetMapping("/api/v1/internal/schema/doc/{code}/rules")
    ApiResponse<DocRuleConfigDTO> getDocRuleConfig(@PathVariable("code") String docCode);

    /**
     * 查询单据事务步骤配置（tx_config，按流转维度）。
     *
     * @param docCode 单据编码
     * @return Saga 事务步骤列表
     */
    @GetMapping("/api/v1/internal/schema/doc/{code}/tx-config")
    ApiResponse<TxConfigDTO> getTxConfig(@PathVariable("code") String docCode);

    /**
     * 查询规则库条目（rule_lib，校验执行时按 uuid 回源/缓存）。
     *
     * @param uuid 规则唯一标识
     * @return 规则定义（含 expression），未命中返回 40401
     */
    @GetMapping("/api/v1/internal/schema/rule/{uuid}")
    ApiResponse<RuleLibDTO> getRuleLib(@PathVariable("uuid") String uuid);

    /**
     * 查询实体定义（entity_schema，仅已发布可见；未发布/不存在返回 40401）。
     *
     * @param code 实体编码
     * @return 实体定义（含 attributes 列表）
     */
    @GetMapping("/api/v1/internal/schema/entity/{code}")
    ApiResponse<EntitySchemaDTO> getEntitySchema(@PathVariable("code") String code);

    /**
     * 全量拉取已发布实体定义（runtime-service 启动预热/定时对账，行数百级一次拉齐）。
     *
     * @return 已发布实体定义列表（不含草稿/停用）
     */
    @GetMapping("/api/v1/internal/schema/entity/published")
    ApiResponse<List<EntitySchemaDTO>> listPublishedEntitySchemas();
}
