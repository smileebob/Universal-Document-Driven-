package com.example.runtime.domain.validation;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.runtime.domain.model.EntitySchema;

/**
 * 校验上下文（五类校验链共享的入参快照）。
 *
 * <p>attributes 为本次校验的目标值：新增=提交值（默认值已回填）；
 * 修改=与存量合并后的全量值。</p>
 */
public final class ValidationContext {

    /** 实体定义 */
    private final EntitySchema schema;

    /** 目标属性值（保持提交顺序） */
    private final Map<String, Object> attributes;

    /** 是否修改场景（修改场景 ref 校验仅针对变化值可扩展，当前同口径全量校验） */
    private final boolean update;

    /** 数据属主 uuid（修改场景；新增场景在占位阶段才有 uuid，可为 null） */
    private final String ownerUuid;

    public ValidationContext(EntitySchema schema, Map<String, Object> attributes,
                             boolean update, String ownerUuid) {
        this.schema = schema;
        this.attributes = attributes == null ? new LinkedHashMap<>() : attributes;
        this.update = update;
        this.ownerUuid = ownerUuid;
    }

    public EntitySchema getSchema() {
        return schema;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public boolean isUpdate() {
        return update;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }
}
