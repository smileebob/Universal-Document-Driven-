package com.example.runtime.domain.model;

import java.util.Locale;

/**
 * 属性数据类型（运行域内部枚举，防腐收口）。
 *
 * <p>映射来源：PRD 12 §1.3 data_type 全集（string/integer/decimal/boolean/date/datetime/ref/table）
 * 与 meta-service-api EntitySchemaDTO 目标契约（STRING/NUMBER/BOOLEAN/DATE/REF）。
 * 阶段一实体属性不涉及 table 明细类型（单据阶段二），映射规则：</p>
 * <ul>
 *   <li>string → STRING；integer/decimal/number → NUMBER；boolean → BOOLEAN；</li>
 *   <li>date → DATE；datetime → DATETIME；ref → REF；table 不支持（实体域）。</li>
 * </ul>
 */
public enum AttributeType {

    /** 字符串（maxLength 约束） */
    STRING,
    /** 数值（整数/小数统一承载） */
    NUMBER,
    /** 布尔 */
    BOOLEAN,
    /** 日期（yyyy-MM-dd 字符串透传存储，design/09 §3.4） */
    DATE,
    /** 日期时间（ISO8601 字符串） */
    DATETIME,
    /** 引用（存被引用实体数据 uuid，design/03 §4.3） */
    REF;

    /**
     * 宽松解析原始类型串（兼容 meta 侧 snake/camel、大小写与 PRD 12 取值）。
     *
     * @param raw 原始类型串（type / data_type 列值）
     * @return 属性类型
     * @throws IllegalArgumentException 未知类型
     */
    public static AttributeType fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("属性类型不可为空");
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "string":
                return STRING;
            case "integer":
            case "decimal":
            case "number":
                return NUMBER;
            case "boolean":
                return BOOLEAN;
            case "date":
                return DATE;
            case "datetime":
                return DATETIME;
            case "ref":
                return REF;
            default:
                throw new IllegalArgumentException("不支持的属性类型: " + raw);
        }
    }
}
