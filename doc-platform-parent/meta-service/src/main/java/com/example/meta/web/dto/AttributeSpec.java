package com.example.meta.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 属性定义请求/视图结构（PRD 11 §3.1 报文 snake_case 契约）。
 *
 * <p>纯数据结构：与 domain 的映射收口于 service 层 SchemaViewMapper
 * （ArchUnit 四层单向依赖守护）。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttributeSpec {

    /** 属性名称 */
    private String name;

    /** 属性编码 */
    private String code;

    /** 数据类型（string/integer/decimal/boolean/date/datetime/ref/table） */
    private String dataType;

    /** 是否必填 */
    private Boolean required;

    /** 是否可见 */
    private Boolean visible;

    /** 是否可编辑 */
    private Boolean editable;

    /** 默认值 */
    private String defaultValue;

    /** ref 目标实体编码 */
    private String refEntityCode;

    /** 是否列表列（prd/11 §3.1：is_list_column） */
    private Boolean isListColumn;

    /** 是否可检索（发布时注册索引子字段，prd/11 §3.1：is_searchable） */
    private Boolean isSearchable;

    /** 是否唯一 */
    private Boolean unique;

    /** ref 展示字段 */
    private String displayField;

    /** 最大长度 */
    private Integer maxLength;

    /** 小数精度 */
    private Integer precision;

    /** 排序序号 */
    private Integer listOrder;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    public Boolean getEditable() {
        return editable;
    }

    public void setEditable(Boolean editable) {
        this.editable = editable;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getRefEntityCode() {
        return refEntityCode;
    }

    public void setRefEntityCode(String refEntityCode) {
        this.refEntityCode = refEntityCode;
    }

    public Boolean getIsListColumn() {
        return isListColumn;
    }

    public void setIsListColumn(Boolean isListColumn) {
        this.isListColumn = isListColumn;
    }

    public Boolean getIsSearchable() {
        return isSearchable;
    }

    public void setIsSearchable(Boolean isSearchable) {
        this.isSearchable = isSearchable;
    }

    public Boolean getUnique() {
        return unique;
    }

    public void setUnique(Boolean unique) {
        this.unique = unique;
    }

    public String getDisplayField() {
        return displayField;
    }

    public void setDisplayField(String displayField) {
        this.displayField = displayField;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    public Integer getPrecision() {
        return precision;
    }

    public void setPrecision(Integer precision) {
        this.precision = precision;
    }

    public Integer getListOrder() {
        return listOrder;
    }

    public void setListOrder(Integer listOrder) {
        this.listOrder = listOrder;
    }
}
