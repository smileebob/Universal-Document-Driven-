package com.example.meta.domain.doc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单据规则绑定领域模型（doc_rule_config 表，RE-05，保存即生效）。
 *
 * <p>结构：create_rules=[uuid...]；status_rules=[{status:10,rules:[uuid...]}]。</p>
 */
public class DocRuleConfig {

    private String docCode;
    private List<String> createRules;
    private List<Map<String, Object>> statusRules;
    private long version;
    private Long updateTime;

    /** 规则绑定仓储（doc_rule_config 单键表） */
    public interface Repository {

        Optional<DocRuleConfig> findByDocCode(String docCode);

        /** 新建（EXPECT_NOT_EXIST） */
        void insert(DocRuleConfig config);

        /** version CAS 更新 */
        void updateWithVersion(DocRuleConfig config);

        /** 全量绑定（规则删除前反引用扫描，行数百级） */
        List<DocRuleConfig> findAll();
    }

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
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

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}
