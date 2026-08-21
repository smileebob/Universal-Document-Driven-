package com.example.common.infrastructure.tablestore.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 分区键构造工具（design/03 §3.2：{@code {code}_{hashN(uuid)}}，hashN = MD5 十六进制前 N 位）。
 *
 * <p>hash 位数一经投产不可变更（分区键是主键）：本期数据表统一 4 位，
 * audit_log 按三年 2000 万行选 6 位（design/03 §3.2 / design/04 §2.3）。</p>
 */
public final class PartitionKeys {

    /** 数据表默认 hash 位数 */
    public static final int DEFAULT_HASH_LENGTH = 4;

    /** audit_log hash 位数（只增不改、三年 2000 万行，design/03 §3.2） */
    public static final int AUDIT_LOG_HASH_LENGTH = 6;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PartitionKeys() {
    }

    /**
     * MD5 十六进制前 N 位。
     *
     * @param value  原值（uuid / log_id / saga_id / 幂等键 / 属性值）
     * @param length 位数 N
     * @return hash 前缀
     */
    public static String hash(String value, int length) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("hash 原值不可为空");
        }
        if (length < 1 || length > 32) {
            throw new IllegalArgumentException("hash 位数越界: " + length);
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
            }
            return sb.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 数据表分区键：{@code {code}_{hash4(uuid)}}
     * （entity_data / doc_data / doc_data_history / event_outbox 同分区设计）。
     *
     * @param code 实体/单据编码
     * @param uuid 实例唯一标识
     * @return 分区键
     */
    public static String dataKey(String code, String uuid) {
        return code + "_" + hash(uuid, DEFAULT_HASH_LENGTH);
    }

    /**
     * audit_log 分区键：{@code log_{hash6(log_id)}}（禁止时间前缀，design/03 §2.10）。
     *
     * @param logId 日志唯一 ID
     * @return 分区键
     */
    public static String logKey(String logId) {
        return "log_" + hash(logId, AUDIT_LOG_HASH_LENGTH);
    }

    /**
     * saga_execution / saga_dead_letter 分区键：{@code saga_{hash4(sagaId)}}。
     *
     * @param sagaId Saga 实例 ID
     * @return 分区键
     */
    public static String sagaKey(String sagaId) {
        return "saga_" + hash(sagaId, DEFAULT_HASH_LENGTH);
    }

    /**
     * idempotent_record 分区键：{@code idem_{hash4(key)}}（design/05 §5.2）。
     *
     * @param idempotencyKey 幂等键原文
     * @return 分区键
     */
    public static String idemKey(String idempotencyKey) {
        return "idem_" + hash(idempotencyKey, DEFAULT_HASH_LENGTH);
    }

    /**
     * rule_lib 分区键：{@code rule_{hash4(ruleUuid)}}（design/03 §2.6）。
     *
     * @param ruleUuid 规则唯一标识
     * @return 分区键
     */
    public static String ruleKey(String ruleUuid) {
        return "rule_" + hash(ruleUuid, DEFAULT_HASH_LENGTH);
    }

    /**
     * unique_index 分区键：{@code {targetCode}_{attrCode}_{hash4(value)}}（design/03 §5.2）。
     *
     * @param targetCode 实体/单据编码
     * @param attrCode   unique 属性编码
     * @param value      属性值原文（已归一化）
     * @return 分区键
     */
    public static String uniqueIndexKey(String targetCode, String attrCode, String value) {
        return targetCode + "_" + attrCode + "_" + hash(value, DEFAULT_HASH_LENGTH);
    }
}
