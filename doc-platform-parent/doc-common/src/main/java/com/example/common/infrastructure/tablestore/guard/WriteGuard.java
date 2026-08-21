package com.example.common.infrastructure.tablestore.guard;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.metadata.PartitionMode;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;

/**
 * 写入侧红线拦截（design/03 §3.3 热点规避 + §7.3 行宽预算，design/08 §2.7）。
 *
 * <ul>
 *   <li>分区键格式校验：HASH 模式表强校验 {@code {code}_{hashN(uuid)}} 后缀；
 *       全部表拒绝时间/日期前缀与单调递增 ID 前缀（红线 1/2）；</li>
 *   <li>大行防护：attributes 序列化 &gt; 256KB 告警日志、&gt; 1.5MB 抛 {@link BizException} 硬拒
 *       （单行硬上限 2MB，为其余列留余量）。</li>
 * </ul>
 */
public final class WriteGuard {

    private static final Logger log = LoggerFactory.getLogger(WriteGuard.class);

    /** attributes 序列化告警阈值：256KB（design/03 §7.3） */
    public static final int ATTRIBUTES_WARN_BYTES = 256 * 1024;

    /** attributes 序列化硬拒阈值：1.5MB（design/03 §7.3） */
    public static final int ATTRIBUTES_REJECT_BYTES = 1536 * 1024;

    /** attributes JSON 列名（design/03 §二各表统一定义） */
    public static final String ATTRIBUTES_COLUMN = "attributes";

    /** HASH 模式分区键后缀：{@code _[0-9a-f]{N}} */
    private static final Pattern HASH_SUFFIX_PATTERN = Pattern.compile(".*_[0-9a-f]{1,16}$");

    /** 流转审计同分区变体 hash 位数（design/03 §6.1 场景 3：{doc_code}_{hash4(uuid)}） */
    private static final int AUDIT_TRANSITION_HASH_LENGTH = 4;

    /** 流转审计同分区变体豁免表（audit_log 自身定义 HASH6，变体 hash4） */
    private static final String AUDIT_LOG_TABLE = "audit_log";

    /** 时间/日期前缀红线：数字开头（如 202608_、雪花 ID 原值） */
    private static final Pattern TIME_PREFIX_PATTERN = Pattern.compile("^\\d.*");

    private final TableMetadataRegistry registry;

    public WriteGuard(TableMetadataRegistry registry) {
        this.registry = registry;
    }

    /**
     * 分区键格式校验（写入前拦截）。
     *
     * @param table 表名
     * @param pk    主键（第一列为分区键）
     * @throws IllegalArgumentException 分区键违反红线
     */
    public void validatePartitionKey(String table, TsPrimaryKey pk) {
        String partitionValue = pk.partitionValueAsString();
        if (partitionValue.isEmpty()) {
            throw new IllegalArgumentException("分区键不可为空: table=" + table);
        }
        // 红线 1/2：禁止时间/日期前缀与单调递增 ID 作分区键前缀
        if (TIME_PREFIX_PATTERN.matcher(partitionValue).matches()) {
            throw new IllegalArgumentException(
                    "分区键禁止数字/时间前缀（热点红线 1/2）: table=" + table + ", partitionKey=" + partitionValue);
        }
        registry.get(table).map(TableDefinition::getPartitionMode)
                .filter(mode -> mode == PartitionMode.HASH)
                .ifPresent(mode -> validateHashPartitionKey(table, partitionValue));
    }

    /**
     * 直接校验给定分区键值（局部事务入口用）。
     *
     * @param table          锚定表名
     * @param partitionValue 分区键值
     */
    public void validatePartitionValue(String table, String partitionValue) {
        validatePartitionKey(table, TsPrimaryKey.of("pk", partitionValue));
    }

    private void validateHashPartitionKey(String table, String partitionValue) {
        TableDefinition def = registry.get(table).orElseThrow();
        if (!HASH_SUFFIX_PATTERN.matcher(partitionValue).matches()) {
            throw new IllegalArgumentException(
                    "分区键不符合 {code}_{hashN(uuid)} 格式: table=" + table + ", partitionKey=" + partitionValue);
        }
        // 精确校验 hash 位数（hashN 一经投产不可变更）
        int underscore = partitionValue.lastIndexOf('_');
        String hashPart = partitionValue.substring(underscore + 1);
        if (hashPart.length() != def.getHashLength()) {
            // 豁免（design/03 §6.1 场景 3）：流转审计行随局部事务以
            // {doc_code}_{hash4(uuid)} 同分区写入 audit_log（表自身定义 HASH6），
            // 允许 hash4 变体；其余表一律强校验
            boolean auditTransitionVariant = AUDIT_LOG_TABLE.equals(table)
                    && hashPart.length() == AUDIT_TRANSITION_HASH_LENGTH;
            if (!auditTransitionVariant) {
                throw new IllegalArgumentException(
                        "分区键 hash 位数与表定义不符（期望 " + def.getHashLength() + " 位）: table=" + table
                                + ", partitionKey=" + partitionValue);
            }
        }
    }

    /**
     * 大行防护：attributes 序列化体积校验（design/03 §7.3）。
     *
     * @param table   表名
     * @param columns 本次写入的属性列
     * @throws BizException TYPE_INVALID（40002）attributes 超 1.5MB 硬拒
     */
    public void validateRowSize(String table, Map<String, TsColumnValue> columns) {
        TsColumnValue attributes = columns.get(ATTRIBUTES_COLUMN);
        if (attributes == null || attributes.getType() != TsColumnValue.Type.STRING) {
            return;
        }
        int bytes = attributes.asString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ATTRIBUTES_REJECT_BYTES) {
            throw new BizException(ErrorCode.TYPE_INVALID,
                    "attributes 序列化体积 " + bytes + "B 超过硬拒阈值 1.5MB（单行上限 2MB）: table=" + table);
        }
        if (bytes > ATTRIBUTES_WARN_BYTES) {
            log.warn("[tablestore][large-row] attributes 序列化体积 {}B 超过告警阈值 256KB: table={}", bytes, table);
        }
    }

    /**
     * 完整写入校验：分区键 + 行宽。
     *
     * @param table   表名
     * @param pk      主键
     * @param columns 属性列
     */
    public void validateWrite(String table, TsPrimaryKey pk, Map<String, TsColumnValue> columns) {
        validatePartitionKey(table, pk);
        validateRowSize(table, columns);
    }
}
