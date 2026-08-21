package com.example.meta.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 更新实体定义请求（PUT /api/v1/schema/entity/{code}，仅草稿态可更新）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityUpdateRequest {

    /** 实体名称 */
    @NotBlank(message = "不可为空")
    private String entityName;

    /** 实体分类 */
    private String entityType;

    /** 描述 */
    private String description;

    /** 属性列表（全量覆盖） */
    private List<AttributeSpec> attributes;

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<AttributeSpec> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<AttributeSpec> attributes) {
        this.attributes = attributes;
    }
}
