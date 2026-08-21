package com.example.meta.api.dto;

import java.io.Serializable;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 实体属性定义 DTO（EntitySchemaDTO.attributes 元素，EC 契约固化）。
 *
 * <p>字段为运行侧消费的最小集：code/name/type/required/unique/refEntityCode/
 * maxLength/defaultValue/sortOrder/displayField。配置侧的展示类字段（visible/editable/
 * is_list_column 等）保留在 meta-service 内部存储，不经本契约外溢。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 属性编码（实体内唯一） */
    private String code;

    /** 属性名称 */
    private String name;

    /** 属性类型（STRING/NUMBER/BOOLEAN/DATE/REF） */
    private AttributeType type;

    /** 是否必填（校验链第①步） */
    private boolean required;

    /** 是否唯一（unique 约束伴生表 CAS，校验链第④步） */
    private boolean unique;

    /** type=REF 时的引用目标实体编码，其余类型为 null */
    private String refEntityCode;

    /** 最大长度（STRING 类型适用，null 表示不限） */
    private Integer maxLength;

    /** 默认值（字符串承载，运行侧按 type 解析） */
    private String defaultValue;

    /** 展示/处理顺序（升序） */
    private Integer sortOrder;

    /** type=REF 时的展示字段 code（被引用实体属性，design/02；null 时运行侧取缺省策略） */
    private String displayField;

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

    public AttributeType getType() {
        return type;
    }

    public void setType(AttributeType type) {
        this.type = type;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
        this.unique = unique;
    }

    public String getRefEntityCode() {
        return refEntityCode;
    }

    public void setRefEntityCode(String refEntityCode) {
        this.refEntityCode = refEntityCode;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getDisplayField() {
        return displayField;
    }

    public void setDisplayField(String displayField) {
        this.displayField = displayField;
    }
}
