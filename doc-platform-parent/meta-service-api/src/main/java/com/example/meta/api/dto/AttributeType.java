package com.example.meta.api.dto;

/**
 * 属性类型枚举（EC 契约，运行侧校验/索引映射的裁决值）。
 *
 * <p>与配置侧 data_type 的映射（meta-service 内部完成）：
 * string→STRING；integer/decimal→NUMBER；boolean→BOOLEAN；
 * date/datetime→DATE；ref→REF；table（明细子表）不经本契约外溢。</p>
 */
public enum AttributeType {

    /** 字符串 */
    STRING,

    /** 数值（integer/decimal 归并） */
    NUMBER,

    /** 布尔 */
    BOOLEAN,

    /** 日期/日期时间（字符串承载 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss） */
    DATE,

    /** 引用（目标为 refEntityCode 指向的已发布实体） */
    REF
}
