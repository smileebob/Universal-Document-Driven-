package com.example.meta.domain.schema;

import java.io.Serializable;

/**
 * 属性定义（domain 模型，PRD 属性全集投影，design/02 §二）。
 *
 * <p>JSON 序列化后落 entity_schema.attributes 列（STRING）；data_type 取值域：
 * string/integer/decimal/boolean/date/datetime/ref/table。</p>
 */
public class AttributeDef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 属性名称 */
    private String name;

    /** 属性编码（实体内唯一） */
    private String code;

    /** 数据类型（string/integer/decimal/boolean/date/datetime/ref/table） */
    private String dataType;

    /** 是否必填（校验链第①步） */
    private boolean required;

    /** 是否可见（列表/详情展示） */
    private boolean visible = true;

    /** 是否可编辑（表单态） */
    private boolean editable = true;

    /** 默认值（字符串承载） */
    private String defaultValue;

    /** type=ref 时的引用目标实体编码 */
    private String refEntityCode;

    /** 是否列表展示列 */
    private boolean listColumn;

    /** 是否参与多元索引检索（发布时注册 attributes Nested 子字段） */
    private boolean searchable;

    /** 是否唯一（unique 约束伴生表 CAS） */
    private boolean unique;

    /** ref 展示字段（目标实体的某属性 code） */
    private String displayField;

    /** 最大长度（string 适用） */
    private Integer maxLength;

    /** 小数精度（decimal 适用） */
    private Integer precision;

    /** 列表/表单排序序号（升序） */
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

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isEditable() {
        return editable;
    }

    public void setEditable(boolean editable) {
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

    public boolean isListColumn() {
        return listColumn;
    }

    public void setListColumn(boolean listColumn) {
        this.listColumn = listColumn;
    }

    public boolean isSearchable() {
        return searchable;
    }

    public void setSearchable(boolean searchable) {
        this.searchable = searchable;
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
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
