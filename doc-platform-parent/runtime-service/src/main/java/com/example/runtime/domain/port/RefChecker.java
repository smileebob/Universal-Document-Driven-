package com.example.runtime.domain.port;

/**
 * 引用数据有效性检查端口（校验链第③步 ref 校验用）。
 *
 * <p>实现走 entity_data 主键 GetRow 点查（写后读强一致，TS-03），
 * 判定目标存在且未逻辑删（status≠0）。</p>
 */
public interface RefChecker {

    /**
     * 判定目标实体数据是否为有效（存在且未逻辑删）数据。
     *
     * @param entityCode 被引用实体编码
     * @param uuid       被引用数据 uuid
     * @return true=有效
     */
    boolean isActiveData(String entityCode, String uuid);
}
