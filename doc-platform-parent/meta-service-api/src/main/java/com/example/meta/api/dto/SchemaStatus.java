package com.example.meta.api.dto;

/**
 * 定义状态枚举（EC 契约）。
 *
 * <p>与存储侧数值映射（design/03 §3.1）：DRAFT=100、PUBLISHED=1、DISABLED=0，
 * 映射由 meta-service 内部完成，契约消费方只感知本枚举。</p>
 */
public enum SchemaStatus {

    /** 草稿（可编辑、可删除，运行侧禁止使用） */
    DRAFT,

    /** 已发布（运行侧可用，校验链/数据写入的唯一依据） */
    PUBLISHED,

    /** 已停用（运行侧禁止新写入，存量数据只读） */
    DISABLED
}
