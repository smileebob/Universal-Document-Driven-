package com.example.meta.domain.txconfig;

import java.util.Optional;

/**
 * 单据事务步骤配置领域模型（tx_config 表，TX-01，1:1 关联单据，保存即生效）。
 *
 * <p>tx_steps 列以 JSON 存「按流转维度的步骤清单」：
 * {@code [{from_status, to_status, steps:[{seq,name,action_url,compensate_url,
 * timeout_ms,retry}]}]}（design/03 §2.8 transition{start,end}+steps 的等价平铺）。
 * 无 version 列：失效广播 version 水位取 update_time（单调递增毫秒）。</p>
 */
public class TxConfig {

    /** 所属单据编码（主键） */
    private String docCode;

    /** 事务步骤清单 JSON（tx_steps 列原文） */
    private String txStepsJson;

    /** 最近更新时间（UTC 毫秒） */
    private Long updateTime;

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }

    public String getTxStepsJson() {
        return txStepsJson;
    }

    public void setTxStepsJson(String txStepsJson) {
        this.txStepsJson = txStepsJson;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * tx_config 仓储端口（实现位于 infrastructure 层）。
     */
    public interface Repository {

        /**
         * 按单据编码查询事务配置。
         *
         * @param docCode 单据编码
         * @return 配置（未配置返回 empty）
         */
        Optional<TxConfig> findByDocCode(String docCode);

        /**
         * 保存（upsert 语义：整行覆盖写）。
         *
         * @param config 配置
         */
        void save(TxConfig config);
    }
}
