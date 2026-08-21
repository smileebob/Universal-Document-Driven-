package com.example.runtime.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 单据规则绑定运行域模型（RE-05 契约适配产物）。
 *
 * <p>触发时机过滤：创建校验取 {@link #createRules}；流转校验取
 * {@code statusRules.get(targetStatus)}。规则 uuid 顺序即执行顺序（短路求值）。</p>
 */
public class DocRuleBindings {

    private final String docCode;
    private final List<String> createRules;
    private final Map<Long, List<String>> statusRules;

    public DocRuleBindings(String docCode, List<String> createRules,
                           Map<Long, List<String>> statusRules) {
        this.docCode = docCode;
        this.createRules = createRules == null ? List.of() : List.copyOf(createRules);
        this.statusRules = statusRules == null ? Map.of() : Map.copyOf(statusRules);
    }

    public String getDocCode() {
        return docCode;
    }

    public List<String> getCreateRules() {
        return createRules;
    }

    public Map<Long, List<String>> getStatusRules() {
        return statusRules;
    }

    /** 流转至目标状态的规则 uuid 列表（未绑定返回空集） */
    public List<String> rulesForTarget(long targetStatus) {
        return statusRules.getOrDefault(targetStatus, List.of());
    }
}
