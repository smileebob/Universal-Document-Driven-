package com.example.runtime.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单据定义运行域模型（MetaSchemaGateway 适配产物，DC 契约不外溢）。
 *
 * <p>状态口径（design/02 §四）：status_defs 为单据实例 current_status 字典
 * （100 暂存初始、1~98 业务态、99 关闭终态，prd/12 §四）；与定义状态
 * （100 草稿/1 生效/0 停用）严格区分。</p>
 */
public class DocDefinition {

    /** 单据初始状态（暂存，prd/12 §四） */
    public static final long INITIAL_STATUS = 100L;

    /** 单据关闭终态 */
    public static final long CLOSED_STATUS = 99L;

    private final String code;
    private final String name;
    private final long schemaVersion;
    private final List<DocAttribute> attributes;
    private final Map<Long, DocStateDef> statusDefs;

    public DocDefinition(String code, String name, long schemaVersion,
                         List<DocAttribute> attributes, Map<Long, DocStateDef> statusDefs) {
        this.code = code;
        this.name = name;
        this.schemaVersion = schemaVersion;
        this.attributes = attributes == null ? List.of() : List.copyOf(attributes);
        this.statusDefs = statusDefs == null ? Map.of() : Collections.unmodifiableMap(statusDefs);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public long getSchemaVersion() {
        return schemaVersion;
    }

    public List<DocAttribute> getAttributes() {
        return attributes;
    }

    public Map<Long, DocStateDef> getStatusDefs() {
        return statusDefs;
    }

    /** 按编码查找头属性（未命中返回 null） */
    public DocAttribute findAttribute(String attrCode) {
        for (DocAttribute attr : attributes) {
            if (attr.getCode().equals(attrCode)) {
                return attr;
            }
        }
        return null;
    }

    /** 状态名（字典未命中时返回状态码字符串） */
    public String stateName(long status) {
        DocStateDef def = statusDefs.get(status);
        return def == null ? String.valueOf(status) : def.getName();
    }

    /** 状态是否允许编辑（字典缺失按不可编辑保守处理；100 暂存恒可编辑） */
    public boolean isEditableStatus(long status) {
        if (status == INITIAL_STATUS) {
            return true;
        }
        DocStateDef def = statusDefs.get(status);
        return def != null && def.isEditable();
    }

    /** 状态是否在字典内登记 */
    public boolean hasStatus(long status) {
        return statusDefs.containsKey(status);
    }

    /**
     * 单据属性定义（头属性与 table 明细子属性同构，subAttributes 递归）。
     */
    public static class DocAttribute {

        private final String code;
        private final String name;
        private final String dataType;
        private final boolean required;
        private final boolean unique;
        private final String refEntityCode;
        private final String displayField;
        private final List<DocAttribute> subAttributes;
        private final boolean editable;
        private final int precision;
        private final int maxLength;

        public DocAttribute(String code, String name, String dataType, boolean required,
                            boolean unique, String refEntityCode, String displayField,
                            List<DocAttribute> subAttributes) {
            this(code, name, dataType, required, unique, refEntityCode, displayField,
                    subAttributes, true, 6, 200);
        }

        public DocAttribute(String code, String name, String dataType, boolean required,
                            boolean unique, String refEntityCode, String displayField,
                            List<DocAttribute> subAttributes, boolean editable,
                            int precision, int maxLength) {
            this.code = code;
            this.name = name;
            this.dataType = dataType == null ? "string" : dataType;
            this.required = required;
            this.unique = unique;
            this.refEntityCode = refEntityCode;
            this.displayField = displayField;
            this.subAttributes = subAttributes == null ? List.of() : List.copyOf(subAttributes);
            this.editable = editable;
            this.precision = precision;
            this.maxLength = maxLength;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getDataType() {
            return dataType;
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

        public String getDisplayField() {
            return displayField;
        }

        public List<DocAttribute> getSubAttributes() {
            return subAttributes;
        }

        /** 修改时是否允许变更（40007 守恒字段） */
        public boolean isEditable() {
            return editable;
        }

        /** DECIMAL 小数位上限 */
        public int getPrecision() {
            return precision;
        }

        /** STRING 最大长度 */
        public int getMaxLength() {
            return maxLength;
        }

        /** 是否明细表格属性（design/02 §四：table 型承载子属性） */
        public boolean isTable() {
            return "table".equalsIgnoreCase(dataType);
        }

        /** 是否引用属性 */
        public boolean isRef() {
            return "ref".equalsIgnoreCase(dataType);
        }
    }

    /**
     * 单据状态字典项（prd/12 §四五状态约定）。
     */
    public static class DocStateDef {

        private final long status;
        private final String name;
        private final boolean editable;
        private final boolean terminal;

        public DocStateDef(long status, String name, boolean editable, boolean terminal) {
            this.status = status;
            this.name = name;
            this.editable = editable;
            this.terminal = terminal;
        }

        public long getStatus() {
            return status;
        }

        public String getName() {
            return name;
        }

        public boolean isEditable() {
            return editable;
        }

        public boolean isTerminal() {
            return terminal;
        }
    }

    /** 便捷构造（测试与适配层用） */
    public static List<DocAttribute> attributes(DocAttribute... attrs) {
        List<DocAttribute> list = new ArrayList<>();
        Collections.addAll(list, attrs);
        return list;
    }
}
