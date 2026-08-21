package com.example.meta.domain.schema;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 实体定义聚合（entity_schema 表 domain 投影，EC-01~04）。
 *
 * <p>列映射（design/03 §3.1）：PK entity_code；entity_name / entity_type /
 * description / attributes(JSON STRING) / status(100|1|0) / creator /
 * create_dept / create_time / update_time / version。</p>
 */
public class EntitySchema implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实体编码（业务主键） */
    private String code;

    /** 实体名称 */
    private String name;

    /** 实体分类（base_data/biz_entity 等，prd/09 分组口径） */
    private String entityType;

    /** 描述 */
    private String description;

    /** 状态 */
    private EntityStatus status = EntityStatus.DRAFT;

    /** 行版本号（创建为 0，每次 CAS 更新/发布/停用 +1） */
    private long version;

    /** 最近发布时间（UTC 毫秒，未发布为 null） */
    private Long publishedAt;

    /** 属性定义列表 */
    private List<AttributeDef> attributes = new ArrayList<>();

    /** 创建人 */
    private String creator;

    /** 创建部门 */
    private String createDept;

    /** 创建时间（UTC 毫秒） */
    private Long createTime;

    /** 更新时间（UTC 毫秒） */
    private Long updateTime;

    public boolean isPublished() {
        return status == EntityStatus.PUBLISHED;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public EntityStatus getStatus() {
        return status;
    }

    public void setStatus(EntityStatus status) {
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

    public List<AttributeDef> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<AttributeDef> attributes) {
        this.attributes = attributes == null ? new ArrayList<>() : attributes;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getCreateDept() {
        return createDept;
    }

    public void setCreateDept(String createDept) {
        this.createDept = createDept;
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
}
