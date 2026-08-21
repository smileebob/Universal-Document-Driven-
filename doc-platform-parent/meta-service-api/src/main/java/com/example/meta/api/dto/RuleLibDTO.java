package com.example.meta.api.dto;

import java.io.Serializable;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 规则库条目 DTO（rule_lib 表契约投影，RE-01/02）。
 *
 * <p>rule_type：{@code expression}（SpEL 表达式，只读沙箱求值）或
 * {@code interface}（接口型占位，阶段二不执行、按跳过处理）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RuleLibDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 规则唯一标识（业务主键，HASH4 分区） */
    private String uuid;

    /** 规则名称 */
    private String ruleName;

    /** 规则类型：expression / interface */
    private String ruleType;

    /** SpEL 表达式（rule_type=expression 时有效，≤500 字符） */
    private String expression;

    /** 接口编码（rule_type=interface 时有效，占位） */
    private String interfaceCode;

    /** 是否启用（停用规则执行时跳过并告警） */
    private Boolean enabled;

    /** 描述 */
    private String description;

    /** 规则版本（保存即生效，缓存失效水位） */
    private Long schemaVersion;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getInterfaceCode() {
        return interfaceCode;
    }

    public void setInterfaceCode(String interfaceCode) {
        this.interfaceCode = interfaceCode;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Long schemaVersion) {
        this.schemaVersion = schemaVersion;
    }
}
