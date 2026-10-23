package com.example.runtime.domain.model;

/**
 * 属性定义（运行域内部模型，schema 驱动校验的依据）。
 *
 * <p>由 infrastructure 层 MetaSchemaGateway 从 meta-service-api EntitySchemaDTO
 * 适配而来（防腐单类收口）；校验链与唯一约束只依赖本模型，不直接触碰 Feign DTO。</p>
 */
public final class AttributeDef {

    /** 属性编码（定义内唯一，PRD 12 §二命名规范） */
    private final String code;

    /** 属性名称（错误文案用） */
    private final String name;

    /** 数据类型 */
    private final AttributeType type;

    /** 是否必填（校验链第①步） */
    private final boolean required;

    /** 是否唯一（unique_index 伴生表 CAS，design/03 §五） */
    private final boolean unique;

    /** REF 类型目标实体编码（其余类型为 null） */
    private final String refEntityCode;

    /** STRING 最大长度（≤0 表示不限制） */
    private final int maxLength;

    /** DECIMAL 小数位上限（≤0 表示不限制） */
    private final int precision;

    /** 默认值原文（缺值时回填，可空） */
    private final Object defaultValue;

    /** 是否可修改（RD-02 后端兜底，缺省 true） */
    private final boolean editable;

    /** 详情 ref 展示字段（缺省 name，RD-04 详情 ref 展开） */
    private final String displayField;

    /** 排序号 */
    private final int sortOrder;

    public AttributeDef(String code, String name, AttributeType type, boolean required,
                        boolean unique, String refEntityCode, int maxLength,
                        Object defaultValue, boolean editable, String displayField,
                        int sortOrder) {
        this(code, name, type, required, unique, refEntityCode, maxLength, 0,
                defaultValue, editable, displayField, sortOrder);
    }

    public AttributeDef(String code, String name, AttributeType type, boolean required,
                        boolean unique, String refEntityCode, int maxLength, int precision,
                        Object defaultValue, boolean editable, String displayField,
                        int sortOrder) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("属性编码不可为空");
        }
        this.code = code;
        this.name = name == null || name.isBlank() ? code : name;
        this.type = type;
        this.required = required;
        this.unique = unique;
        this.refEntityCode = refEntityCode;
        this.maxLength = maxLength;
        this.precision = precision;
        this.defaultValue = defaultValue;
        this.editable = editable;
        this.displayField = displayField == null || displayField.isBlank() ? "name" : displayField;
        this.sortOrder = sortOrder;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AttributeType getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isUnique() {
        return unique;
    }

    public String getRefEntityCode() {
        return refEntityCode;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public int getPrecision() {
        return precision;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public boolean isEditable() {
        return editable;
    }

    public String getDisplayField() {
        return displayField;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
