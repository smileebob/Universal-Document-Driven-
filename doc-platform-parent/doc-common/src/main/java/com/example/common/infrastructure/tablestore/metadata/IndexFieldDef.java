package com.example.common.infrastructure.tablestore.metadata;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 多元索引字段定义（JSON 定义文件元素，design/03 §四）。
 *
 * <p>attributes Nested 容器的 {@code subFields} 为动态注册预留点：
 * schema 发布时由 meta-service 按 is_searchable 属性在线追加子字段（design/03 §4.3），
 * V1 定义中保持空数组。</p>
 */
public class IndexFieldDef {

    /** 索引字段类型常量 */
    public static final String TYPE_KEYWORD = "KEYWORD";
    public static final String TYPE_LONG = "LONG";
    public static final String TYPE_DOUBLE = "DOUBLE";
    public static final String TYPE_BOOLEAN = "BOOLEAN";
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_NESTED = "NESTED";

    private String name;
    private String type = TYPE_KEYWORD;

    /** Nested 容器的 IsArray 标记（relations / related_forms 等数组列） */
    private boolean isArray;

    /** Nested 子字段（动态注册预留点，V1 为空） */
    private List<IndexFieldDef> subFields = new ArrayList<>();

    /** 备注（如「动态注册预留」） */
    private String comment;

    public IndexFieldDef() {
    }

    public IndexFieldDef(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @JsonProperty("isArray")
    public boolean isArray() {
        return isArray;
    }

    /**
     * 显式绑定 JSON 键 {@code isArray}：Jackson 默认从 boolean getter 推断属性名为
     * {@code array}，定义文件的 {@code "isArray": true} 会被静默忽略（任务 #9：
     * 机制化派生读 V1 基线时 relations/related_forms 丢失 IsArray 标记）。
     */
    @JsonProperty("isArray")
    public void setArray(boolean array) {
        isArray = array;
    }

    public List<IndexFieldDef> getSubFields() {
        return subFields;
    }

    public void setSubFields(List<IndexFieldDef> subFields) {
        this.subFields = subFields == null ? new ArrayList<>() : subFields;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
