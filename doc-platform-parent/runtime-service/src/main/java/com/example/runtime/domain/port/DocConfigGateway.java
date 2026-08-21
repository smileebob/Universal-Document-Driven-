package com.example.runtime.domain.port;

import java.util.List;

import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.DocRuleBindings;
import com.example.runtime.domain.model.DocStateMachine;
import com.example.runtime.domain.model.RuleDefinition;
import com.example.runtime.domain.saga.TxPlan;

/**
 * 单据配置回源端口（doc 系五域：单据定义/状态机/规则绑定/规则库/事务配置，A-04 防腐）。
 *
 * <p>实现位于 infrastructure 层（MetaSchemaGateway），全部读取经两级缓存
 * （Caffeine L2 + Redis L1）+ schema-invalidation 广播失效；domain/service
 * 仅消费本端口，不触碰 meta-service-api 契约 DTO。</p>
 */
public interface DocConfigGateway {

    /**
     * 查询已发布单据定义。
     *
     * @param docCode 单据编码
     * @return 单据定义
     * @throws com.example.common.exception.BizException 40401 不存在/未发布；50000 依赖不可用
     */
    DocDefinition getDocSchema(String docCode);

    /**
     * 查询状态机配置（保存即生效，无发布态约束；未配置返回空流转表）。
     *
     * @param docCode 单据编码
     * @return 状态机
     */
    DocStateMachine getStateMachine(String docCode);

    /**
     * 查询规则绑定配置（未配置返回空绑定）。
     *
     * @param docCode 单据编码
     * @return 规则绑定
     */
    DocRuleBindings getDocRuleBindings(String docCode);

    /**
     * 查询规则定义。
     *
     * @param uuid 规则唯一标识
     * @return 规则定义
     * @throws com.example.common.exception.BizException 40401 规则不存在
     */
    RuleDefinition getRule(String uuid);

    /**
     * 查询单据事务步骤配置（tx_config，按流转维度；未配置返回空列表）。
     *
     * <p>Saga 编排器在流转事务外按 (from_status, to_status) 匹配计划。</p>
     *
     * @param docCode 单据编码
     * @return 事务计划列表（每条对应一个流转）
     */
    List<TxPlan> getTxPlans(String docCode);
}
