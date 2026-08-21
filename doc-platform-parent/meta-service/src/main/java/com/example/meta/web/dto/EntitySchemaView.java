package com.example.meta.web.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 实体定义详情视图（GET /api/v1/schema/entity/{code} 响应 data，
 * PRD 11 §3.1 口径：status 为存储数值 100/1/0，version 为行版本号）。
 *
 * <p>纯数据结构：与 domain 的映射收口于 service 层 SchemaViewMapper
 * （ArchUnit 四层单向依赖守护）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntitySchemaView {

    private String entityCode;
    private String entityName;
    private String entityType;
    private String description;
    private int status;
    private long version;
    private Long publishedAt;
    private Long createTime;
    private Long updateTime;
    private List<AttributeSpec> attributes = new ArrayList<>();

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

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Long publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    public List<AttributeSpec> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<AttributeSpec> attributes) {
        this.attributes = attributes == null ? new ArrayList<>() : attributes;
    }
}
