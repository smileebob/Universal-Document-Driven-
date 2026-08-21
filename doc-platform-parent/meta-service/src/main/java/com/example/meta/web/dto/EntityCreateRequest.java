package com.example.meta.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 创建实体定义请求（POST /api/v1/schema/entity，PRD 11 §3.1 报文）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityCreateRequest {

    /** 实体编码（小写字母开头，小写字母/数字/下划线，2~64 位） */
    @NotBlank(message = "不可为空")
    @Pattern(regexp = "[a-z][a-z0-9_]{1,63}", message = "格式不正确（小写字母开头，仅限小写字母/数字/下划线）")
    private String entityCode;

    /** 实体名称 */
    @NotBlank(message = "不可为空")
    private String entityName;

    /** 实体分类（base_data/biz_entity 等） */
    private String entityType;

    /** 描述 */
    private String description;

    /** 属性列表（创建时可为空，发布时校验 ≥1） */
    private List<AttributeSpec> attributes;

    public String getEntityCode() {
        return entityCode;
    }

    public void setEntityCode(String entityCode) {
        this.entityCode = entityCode;
    }

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
