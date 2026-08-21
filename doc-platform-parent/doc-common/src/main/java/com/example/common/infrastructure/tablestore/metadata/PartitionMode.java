package com.example.common.infrastructure.tablestore.metadata;

/**
 * 分区键模式（design/03 §3.1 两类主键模式）。
 */
public enum PartitionMode {

    /** 数据表 hash 打散模式：分区键格式 {@code {code}_{hashN(uuid)}}，写入侧强校验 */
    HASH,

    /** 配置表单键模式：业务 code 直接作分区键（行数少、读多写少、字典序遍历） */
    PLAIN
}
