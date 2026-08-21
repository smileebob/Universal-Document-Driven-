package com.example.runtime.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * 实体定义（运行域内部模型，schema 驱动校验的依据）。
 *
 * <p>由 infrastructure 层 MetaSchemaGateway 从 meta-service-api EntitySchemaDTO
 * 适配而来（防腐单类收口，leader 裁决批准的 DTO 防腐适配方案）；
 * service/domain 只依赖本模型，不直接触碰 Feign DTO。</p>
 */
public final class EntitySchema {

    /** 定义状态：生效（PRD 12 枚举，1=生效） */
    public static final int STATUS_ACTIVE = 1;

    /** 实体编码（业务主键） */
    private final String code;

    /** 实体名称 */
    private final String name;

    /** 定义状态：1=生效、0=停用 */
    private final int status;

    /** 属性定义列表（按 sortOrder 稳定排序） */
    private final List<AttributeDef> attributes;

    public EntitySchema(String code, String name, int status, List<AttributeDef> attributes) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("实体编码不可为空");
        }
        this.code = code;
        this.name = name == null || name.isBlank() ? code : name;
        this.status = status;
        this.attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getStatus() {
        return status;
    }

    public List<AttributeDef> getAttributes() {
        return attributes;
    }

    /** 定义是否处于生效态（仅生效定义可承载数据写入） */
    public boolean isActive() {
        return status == STATUS_ACTIVE;
    }

    /**
     * 按属性编码查找属性定义。
     *
     * @param attrCode 属性编码
     * @return 属性定义（未定义返回 empty）
     */
    public Optional<AttributeDef> findAttribute(String attrCode) {
        for (AttributeDef attribute : attributes) {
            if (attribute.getCode().equals(attrCode)) {
                return Optional.of(attribute);
            }
        }
        return Optional.empty();
    }

    /**
     * unique 属性定义子集（唯一约束编排用）。
     *
     * @return unique=true 的属性列表
     */
    public List<AttributeDef> uniqueAttributes() {
        return attributes.stream().filter(AttributeDef::isUnique).toList();
    }
}
