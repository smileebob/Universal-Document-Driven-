package com.example.meta.domain.schema;

/**
 * 实体定义状态（domain 视角，design/03 §3.1 存储数值 100/1/0）。
 */
public enum EntityStatus {

    /** 草稿（100）：可编辑、可删除，运行侧禁止使用（40401） */
    DRAFT(100L),

    /** 已发布（1）：运行侧校验链/数据写入的唯一依据 */
    PUBLISHED(1L),

    /** 已停用（0）：禁止新写入，存量只读 */
    DISABLED(0L);

    private final long storageValue;

    EntityStatus(long storageValue) {
        this.storageValue = storageValue;
    }

    public long getStorageValue() {
        return storageValue;
    }

    /**
     * 按存储数值反查状态。
     *
     * @param value 存储值（100/1/0）
     * @return 状态枚举
     * @throws IllegalArgumentException 未登记的状态值
     */
    public static EntityStatus fromStorage(long value) {
        for (EntityStatus status : values()) {
            if (status.storageValue == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未登记的定义状态值: " + value);
    }
}
