package com.example.meta.domain.doc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 状态机配置领域模型（state_machine 表，SM-01/02，保存即生效）。
 *
 * <p>transitions 结构：{@code [{start:100,end:[10]},...]}；states 为状态字典
 * （与 doc_schema.status_defs 同源口径，元素 {status,name,editable,terminal}）。</p>
 */
public class StateMachineConfig {

    private String docCode;
    private List<Map<String, Object>> states;
    private List<Map<String, Object>> transitions;
    private long version;
    private Long updateTime;

    /** 状态机仓储（state_machine 单键表） */
    public interface Repository {

        Optional<StateMachineConfig> findByDocCode(String docCode);

        /** 新建（EXPECT_NOT_EXIST） */
        void insert(StateMachineConfig config);

        /** version CAS 更新 */
        void updateWithVersion(StateMachineConfig config);
    }

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }

    public List<Map<String, Object>> getStates() {
        return states;
    }

    public void setStates(List<Map<String, Object>> states) {
        this.states = states;
    }

    public List<Map<String, Object>> getTransitions() {
        return transitions;
    }

    public void setTransitions(List<Map<String, Object>> transitions) {
        this.transitions = transitions;
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
