package com.example.meta.service.schema;

import java.util.ArrayList;
import java.util.List;

import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.web.dto.AttributeSpec;
import com.example.meta.web.dto.EntitySchemaView;

/**
 * domain ↔ web 视图映射（service 层收口，web.dto 保持纯数据不依赖 domain，
 * ArchUnit 四层单向依赖守护）。
 */
public final class SchemaViewMapper {

    private SchemaViewMapper() {
    }

    /**
     * domain 定义 → 详情视图（PRD 11 §3.1：status 存储数值 100/1/0）。
     *
     * @param schema domain 定义
     * @return 视图
     */
    public static EntitySchemaView toView(EntitySchema schema) {
        EntitySchemaView view = new EntitySchemaView();
        view.setEntityCode(schema.getCode());
        view.setEntityName(schema.getName());
        view.setEntityType(schema.getEntityType());
        view.setDescription(schema.getDescription());
        view.setStatus((int) schema.getStatus().getStorageValue());
        view.setVersion(schema.getVersion());
        view.setPublishedAt(schema.getPublishedAt());
        view.setCreateTime(schema.getCreateTime());
        view.setUpdateTime(schema.getUpdateTime());
        List<AttributeSpec> specs = new ArrayList<>(schema.getAttributes().size());
        for (AttributeDef def : schema.getAttributes()) {
            specs.add(toSpec(def));
        }
        view.setAttributes(specs);
        return view;
    }

    /**
     * 请求结构 → domain 属性定义（Boolean null 按默认值处理）。
     *
     * @param spec 请求结构
     * @return domain 属性
     */
    public static AttributeDef toDef(AttributeSpec spec) {
        AttributeDef def = new AttributeDef();
        def.setName(spec.getName());
        def.setCode(spec.getCode());
        def.setDataType(spec.getDataType());
        def.setRequired(Boolean.TRUE.equals(spec.getRequired()));
        def.setVisible(spec.getVisible() == null || spec.getVisible());
        def.setEditable(spec.getEditable() == null || spec.getEditable());
        def.setDefaultValue(spec.getDefaultValue());
        def.setRefEntityCode(spec.getRefEntityCode());
        def.setListColumn(Boolean.TRUE.equals(spec.getIsListColumn()));
        def.setSearchable(Boolean.TRUE.equals(spec.getIsSearchable()));
        def.setUnique(Boolean.TRUE.equals(spec.getUnique()));
        def.setDisplayField(spec.getDisplayField());
        def.setMaxLength(spec.getMaxLength());
        def.setPrecision(spec.getPrecision());
        def.setListOrder(spec.getListOrder());
        return def;
    }

    /**
     * domain 属性 → 视图结构。
     *
     * @param def domain 属性
     * @return 视图结构
     */
    public static AttributeSpec toSpec(AttributeDef def) {
        AttributeSpec spec = new AttributeSpec();
        spec.setName(def.getName());
        spec.setCode(def.getCode());
        spec.setDataType(def.getDataType());
        spec.setRequired(def.isRequired());
        spec.setVisible(def.isVisible());
        spec.setEditable(def.isEditable());
        spec.setDefaultValue(def.getDefaultValue());
        spec.setRefEntityCode(def.getRefEntityCode());
        spec.setIsListColumn(def.isListColumn());
        spec.setIsSearchable(def.isSearchable());
        spec.setUnique(def.isUnique());
        spec.setDisplayField(def.getDisplayField());
        spec.setMaxLength(def.getMaxLength());
        spec.setPrecision(def.getPrecision());
        spec.setListOrder(def.getListOrder());
        return spec;
    }
}
