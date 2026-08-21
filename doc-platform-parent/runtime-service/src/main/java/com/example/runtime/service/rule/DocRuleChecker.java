package com.example.runtime.service.rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.rule.RuleEvalResult;
import com.example.common.rule.RuleExecutor;
import com.example.common.rule.RuleSpec;
import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.RuleDefinition;
import com.example.runtime.domain.port.DocConfigGateway;

/**
 * 单据规则校验服务（RE-05 运行时执行：创建时机 create_rules /
 * 流转时机 status_rules[target]，design/08 §四）。
 *
 * <p>执行语义：全部读取经 {@link DocConfigGateway} 两级缓存；执行器为
 * doc-common {@link RuleExecutor}（SpEL 只读沙箱 + 2s 超时 + 编译缓存）；
 * 停用/接口型规则跳过不计失败。</p>
 *
 * <p><b>relations 契约（引用关系列表，非引用数据视图）</b>：上下文
 * {@code relations} 为 ref 引用关系五元组列表，元素
 * {@code {source_code, source_uuid, target_code, target_uuid, source_attribute_code}}；
 * 规则表达式只允许引用 {@code #attributes}（头属性含 table 明细行），
 * 禁止经 {@code #relations} 引用目标对象属性。</p>
 */
@Service
public class DocRuleChecker {

    private static final Logger log = LoggerFactory.getLogger(DocRuleChecker.class);

    private final DocConfigGateway configGateway;
    private final RuleExecutor ruleExecutor;

    public DocRuleChecker(DocConfigGateway configGateway, RuleExecutor ruleExecutor) {
        this.configGateway = configGateway;
        this.ruleExecutor = ruleExecutor;
    }

    /**
     * 创建时机规则校验（create_rules 绑定）。
     *
     * @param sourceUuid 单据实例标识（创建时服务端预生成，供 relations 五元组引用）
     * @return 失败明细列表（空=全部通过；元素 {rule_uuid,rule_name,reason}）
     */
    public List<Map<String, Object>> checkCreate(String docCode, String sourceUuid,
                                                 DocDefinition definition,
                                                 Map<String, Object> attributes, String operator) {
        List<String> ruleUuids = configGateway.getDocRuleBindings(docCode).getCreateRules();
        return executeRules(ruleUuids, buildVariables(docCode, sourceUuid, definition,
                attributes, DocDefinition.INITIAL_STATUS, DocDefinition.INITIAL_STATUS, operator));
    }

    /**
     * 流转时机规则校验（status_rules[target_status] 绑定）。
     *
     * @return 失败明细列表（空=全部通过）
     */
    public List<Map<String, Object>> checkTransition(String docCode, String sourceUuid,
                                                     long currentStatus, long targetStatus,
                                                     DocDefinition definition,
                                                     Map<String, Object> attributes, String operator) {
        List<String> ruleUuids = configGateway.getDocRuleBindings(docCode).rulesForTarget(targetStatus);
        return executeRules(ruleUuids, buildVariables(docCode, sourceUuid, definition,
                attributes, currentStatus, targetStatus, operator));
    }

    // ==================== 内部实现 ====================

    /** 顺序执行规则（uuid 顺序即执行顺序；失败聚合，skip 不计入） */
    private List<Map<String, Object>> executeRules(List<String> ruleUuids,
                                                   Map<String, Object> variables) {
        List<Map<String, Object>> failed = new ArrayList<>();
        if (ruleUuids.isEmpty()) {
            return failed;
        }
        for (String uuid : ruleUuids) {
            RuleDefinition rule = configGateway.getRule(uuid);
            RuleSpec spec = new RuleSpec(rule.getUuid(), rule.getRuleName(),
                    rule.getRuleType(), rule.getExpression(), rule.isEnabled());
            RuleEvalResult result = ruleExecutor.execute(spec, variables);
            if (!result.isPassed()) {
                log.warn("[doc-rule] 规则未通过: uuid={}, reason={}", uuid, result.getReason());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("rule_uuid", result.getRuleUuid());
                detail.put("rule_name", result.getRuleName());
                detail.put("reason", result.getReason() == null ? "规则未通过" : result.getReason());
                failed.add(detail);
            }
        }
        return failed;
    }

    /** 组装执行变量（attributes/relations/current_status/target_status/creator/sys_time） */
    private Map<String, Object> buildVariables(String docCode, String sourceUuid,
                                               DocDefinition definition,
                                               Map<String, Object> attributes,
                                               long currentStatus, long targetStatus,
                                               String operator) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("attributes", attributes == null ? Map.of() : attributes);
        variables.put("relations", expandRelations(docCode, sourceUuid, definition, attributes));
        variables.put("current_status", currentStatus);
        variables.put("target_status", targetStatus);
        variables.put("creator", operator == null ? "" : operator);
        variables.put("sys_time", System.currentTimeMillis());
        return variables;
    }

    /**
     * ref 引用关系展开（五元组契约：{source_code, source_uuid, target_code,
     * target_uuid, source_attribute_code}）。
     *
     * <p>范围：头属性 ref + table 明细行内 ref 子属性；每次引用产生一条关系记录，
     * 不读取目标实体数据（规则禁止经 relations 访问目标属性，引用完整性由
     * 校验链 ③ 步保障）。</p>
     */
    private List<Map<String, Object>> expandRelations(String docCode, String sourceUuid,
                                                      DocDefinition definition,
                                                      Map<String, Object> attributes) {
        List<Map<String, Object>> relations = new ArrayList<>();
        if (attributes == null || attributes.isEmpty()) {
            return relations;
        }
        for (DocDefinition.DocAttribute attr : definition.getAttributes()) {
            Object value = attributes.get(attr.getCode());
            if (attr.isRef() && value instanceof String target && !target.isBlank()) {
                relations.add(relationEntry(docCode, sourceUuid, attr, target));
            } else if (attr.isTable() && value instanceof List<?> rows) {
                // 明细行内 ref 子属性：逐行逐引用产生关系记录
                for (DocDefinition.DocAttribute sub : attr.getSubAttributes()) {
                    if (!sub.isRef()) {
                        continue;
                    }
                    for (Object rowObj : rows) {
                        if (rowObj instanceof Map<?, ?> row) {
                            Object subValue = row.get(sub.getCode());
                            if (subValue instanceof String subTarget && !subTarget.isBlank()) {
                                relations.add(relationEntry(docCode, sourceUuid, sub, subTarget));
                            }
                        }
                    }
                }
            }
        }
        return relations;
    }

    /** 引用关系五元组条目 */
    private Map<String, Object> relationEntry(String docCode, String sourceUuid,
                                              DocDefinition.DocAttribute attr, String targetUuid) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source_code", docCode);
        entry.put("source_uuid", sourceUuid);
        entry.put("target_code", attr.getRefEntityCode());
        entry.put("target_uuid", targetUuid);
        entry.put("source_attribute_code", attr.getCode());
        return entry;
    }
}
