package com.example.meta.api.dto;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 事务步骤配置 DTO（tx_config 表契约投影，按流转维度，TX-01 / design/03 §2.8）。
 *
 * <p>结构：{@code transitions[]} 每条对应一个流转（from_status → to_status），
 * 内含 {@code steps[]} 按 seq 升序正向执行、逆序冲正（design/05 §2.5）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TxConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属单据编码 */
    private String docCode;

    /** 按流转维度的事务步骤清单 */
    private List<TxTransitionDTO> transitions;

    /** 最近更新时间（UTC 毫秒，兼作失效广播 version 水位） */
    private Long updateTime;

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }

    public List<TxTransitionDTO> getTransitions() {
        return transitions;
    }

    public void setTransitions(List<TxTransitionDTO> transitions) {
        this.transitions = transitions;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    /** 单个流转的事务步骤组（TX-01：transition{start,end} + steps[]） */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TxTransitionDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 起始状态 */
        private Long fromStatus;

        /** 目标状态 */
        private Long toStatus;

        /** 步骤清单（seq 升序执行） */
        private List<TxStepDTO> steps;

        public Long getFromStatus() {
            return fromStatus;
        }

        public void setFromStatus(Long fromStatus) {
            this.fromStatus = fromStatus;
        }

        public Long getToStatus() {
            return toStatus;
        }

        public void setToStatus(Long toStatus) {
            this.toStatus = toStatus;
        }

        public List<TxStepDTO> getSteps() {
            return steps;
        }

        public void setSteps(List<TxStepDTO> steps) {
            this.steps = steps;
        }
    }

    /** 单个事务步骤（prd/04 TX-01 字段集） */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TxStepDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 步骤序号（1 起，升序执行/逆序冲正） */
        private Integer seq;

        /** 步骤名（SAP 同步 / APS 排程 / 通知 等） */
        private String name;

        /** 正向执行端点（出站 HTTP POST） */
        private String actionUrl;

        /** 冲正端点（可空=该步无冲正动作） */
        private String compensateUrl;

        /** 超时毫秒（默认 10000） */
        private Long timeoutMs;

        /** 连接类异常重试次数（默认 0；平台封顶 3） */
        private Integer retry;

        public Integer getSeq() {
            return seq;
        }

        public void setSeq(Integer seq) {
            this.seq = seq;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getActionUrl() {
            return actionUrl;
        }

        public void setActionUrl(String actionUrl) {
            this.actionUrl = actionUrl;
        }

        public String getCompensateUrl() {
            return compensateUrl;
        }

        public void setCompensateUrl(String compensateUrl) {
            this.compensateUrl = compensateUrl;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getRetry() {
            return retry;
        }

        public void setRetry(Integer retry) {
            this.retry = retry;
        }
    }
}
