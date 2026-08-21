package com.example.meta.domain.doc;

import java.util.List;
import java.util.Optional;

/**
 * 规则库条目领域模型（rule_lib 表，HASH4 分区，RE-01/02）。
 */
public class RuleLibEntry {

    /** 表达式型规则 */
    public static final String TYPE_EXPRESSION = "expression";

    /** 接口型规则（占位） */
    public static final String TYPE_INTERFACE = "interface";

    private String uuid;
    private String ruleName;
    private String ruleType;
    private String expression;
    private String interfaceCode;
    private boolean enabled = true;
    private String description;
    private long version;
    private String creator;
    private Long createTime;
    private Long updateTime;

    /** 规则库仓储（rule_lib HASH4 分区表） */
    public interface Repository {

        Optional<RuleLibEntry> findByUuid(String uuid);

        /** 新建（EXPECT_NOT_EXIST） */
        void insert(RuleLibEntry entry);

        /** version CAS 更新 */
        void updateWithVersion(RuleLibEntry entry);

        /** 条件删除（EXPECT_EXIST + version 快照 CAS） */
        void deleteByUuidIfVersion(String uuid, long expectVersion);

        List<RuleLibEntry> findAll();
    }

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}
