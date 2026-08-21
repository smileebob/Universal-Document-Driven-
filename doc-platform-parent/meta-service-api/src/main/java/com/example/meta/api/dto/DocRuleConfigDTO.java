package com.example.meta.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 单据规则绑定 DTO（doc_rule_config 表契约投影，RE-05）。
 *
 * <p>结构按 prd/04 RE-05：{@code {create_rules:[uuid...],
 * status_rules:[{status:10, rules:[uuid...]}]}}。运行时按触发时机过滤：
 * 创建校验取 create_rules；流转至目标状态校验取 status_rules 中
 * status == target_status 的规则集（绑定顺序执行，短路求值）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DocRuleConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属单据编码 */
    private String docCode;

    /** 配置版本（保存即生效，版本用于失效广播乱序过滤） */
    private Long schemaVersion;

    /** 创建时机规则 uuid 列表（绑定顺序即执行顺序） */
    private List<String> createRules;

    /** 状态时机规则绑定，元素 {@code {status:Integer, rules:[uuid...]}} */
    private List<Map<String, Object>> statusRules;

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }

    public Long getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Long schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public List<String> getCreateRules() {
        return createRules;
    }

    public void setCreateRules(List<String> createRules) {
        this.createRules = createRules;
    }

    public List<Map<String, Object>> getStatusRules() {
        return statusRules;
    }

    public void setStatusRules(List<Map<String, Object>> statusRules) {
        this.statusRules = statusRules;
    }
}
