package com.example.runtime.domain.port;

import java.util.Optional;

/**
 * unique 约束伴生表端口（unique_index，design/03 §五）。
 *
 * <p>单行 CAS：分区键 {@code {target_code}_{attr_code}_{hash4(value)}} + 主键二列 unique_value，
 * 占位以 PutRow + EXPECT_NOT_EXIST 实现强一致。</p>
 *
 * <p><b>逻辑删语义（leader 裁决，按 design/03 §5.6）</b>：数据逻辑删后唯一行保留、
 * 值仍视为占用，不做释放；孤儿行（主行物理缺失）由对账任务骨架清理。</p>
 */
public interface UniqueIndexStore {

    /**
     * 占用唯一值（EXPECT_NOT_EXIST CAS）。
     *
     * @param targetCode 实体/单据编码
     * @param attrCode   unique 属性编码
     * @param value      归一化后的属性值
     * @param ownerUuid  占用方数据 uuid
     * @return true=占位成功；false=值已被占用（含 hash 撞位且原值相同的既占行）
     */
    boolean tryReserve(String targetCode, String attrCode, String value, String ownerUuid);

    /**
     * 条件释放唯一值占位（仅当占位仍属于 expectedOwner 时删除，DATA-02）。
     *
     * @param targetCode    实体/单据编码
     * @param attrCode      unique 属性编码
     * @param value         归一化后的属性值
     * @param expectedOwner 期望属主 uuid（防止迟到任务误删新属主占位）
     * @return true=释放成功；false=占位不存在或已易主（未删除）
     */
    boolean release(String targetCode, String attrCode, String value, String expectedOwner);

    /**
     * 查询唯一值占位行的属主（对账骨架用）。
     *
     * @param targetCode 实体/单据编码
     * @param attrCode   unique 属性编码
     * @param value      归一化后的属性值
     * @return 属主数据 uuid（未占用返回 empty）
     */
    Optional<String> findOwner(String targetCode, String attrCode, String value);
}
