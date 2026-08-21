package com.example.meta.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 状态机配置 DTO（state_machine 表契约投影，SM-01/02）。
 *
 * <p>结构按 prd/04 SM-01：{@code transitions=[{start:100,end:[10]},...]}；
 * states 为状态字典投影（与 doc_schema.status_defs 同源口径）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class StateMachineDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属单据编码 */
    private String docCode;

    /** 配置版本（保存即生效，版本用于失效广播乱序过滤） */
    private Long schemaVersion;

    /** 状态定义列表，元素 {@code {status,name,editable,terminal}} */
    private List<Map<String, Object>> states;

    /** 流转路径列表，元素 {@code {start:Integer, end:[Integer...]}} */
    private List<Map<String, Object>> transitions;

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
}
