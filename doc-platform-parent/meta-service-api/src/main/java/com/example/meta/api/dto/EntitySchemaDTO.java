package com.example.meta.api.dto;

import java.io.Serializable;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * 实体定义 DTO（entity_schema 表契约投影，EC-01~04 阶段一契约固化）。
 *
 * <p>runtime-service 经 {@code MetaSchemaClient#getEntitySchema} 消费本结构做
 * 校验链/索引注册/ref 展开；字段名以本类为唯一契约，不得偏离。</p>
 *
 * <p>口径说明：{@code status} 为枚举（DRAFT/PUBLISHED/DISABLED），与存储侧
 * 数值（100/1/0，design/03 §3.1）的映射由 meta-service 内部完成；
 * {@code objectType} 恒为 {@code "entity"}，与单据定义（doc）区分。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EntitySchemaDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实体编码（业务主键，entity_schema 分区键） */
    private String code;

    /** 实体名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 定义对象类型，恒为 "entity" */
    private String objectType = "entity";

    /** 定义状态 */
    private SchemaStatus status;

    /** 行版本号（发布/停用时自增，前端缓存失效比对，prd/11 §一补充） */
    private long version;

    /** 最近发布时间（UTC 毫秒；未发布为 null） */
    private Long publishedAt;

    /** 属性定义列表 */
    private List<AttributeDTO> attributes;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public SchemaStatus getStatus() {
        return status;
    }

    public void setStatus(SchemaStatus status) {
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

    public List<AttributeDTO> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<AttributeDTO> attributes) {
        this.attributes = attributes;
    }
}
