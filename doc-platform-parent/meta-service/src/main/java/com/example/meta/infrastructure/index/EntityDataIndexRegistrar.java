package com.example.meta.infrastructure.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntitySchemaRepository;
import com.example.meta.infrastructure.repository.MetaTableNames;

/**
 * entity_data 多元索引动态子字段注册器（design/03 §4.3）。
 *
 * <p>实体发布时按 attributes 增量补注册 {@code entity_data_index} 的
 * attributes Nested 子字段：仅 {@code is_searchable=true} 的属性注册，
 * table（明细子表）永不注册；类型映射 string→KEYWORD、integer→LONG、
 * decimal→DOUBLE、boolean→BOOLEAN、date/datetime→KEYWORD、ref→KEYWORD。
 * relations Nested 子字段固定注册 {@link RelationSubFields}（code/uuid/attribute_code，
 * RD-09/11 关联反查与 ref 关联查询实际引用全集）。</p>
 *
 * <p><b>并集派生防收窄（任务 #10 收口）</b>：reconcile 是「期望集缺失即整体重建」，
 * 若动态子字段只从触发发布的那一个 schema 派生，临时 schema 发布会导致
 * attributes 丢列收窄。现改为聚合全部已发布（PUBLISHED）实体 schema 的
 * is_searchable 属性并集（同名以当前发布 schema 为准，显式并入），任何一次
 * 发布触发的 reconcile 都重建为全量超集。</p>
 *
 * <p>幂等：经 {@link TablestoreTemplate#reconcileSearchIndex} 字段对账注册
 * （描述现有索引 → 求差集 → 已覆盖跳过 / 缺失删除重建；OTS UpdateSearchIndex
 * 不支持增量追加字段，重建为唯一落地路径，权衡见实现类注释）。
 * 注册失败仅 WARN 不阻断发布（缓存 TTL 兜底 + 下次发布重补）。</p>
 */
@Component
public class EntityDataIndexRegistrar {

    private static final Logger log = LoggerFactory.getLogger(EntityDataIndexRegistrar.class);

    private final TablestoreTemplate template;
    private final EntitySchemaRepository schemaRepository;

    public EntityDataIndexRegistrar(TablestoreTemplate template, EntitySchemaRepository schemaRepository) {
        this.template = template;
        this.schemaRepository = schemaRepository;
    }

    /**
     * 发布联动注册（尽力而为，失败不抛业务异常）。
     *
     * @param currentAttributes 当前正在发布的实体属性列表（显式并入并集，
     *                          不依赖其已落 PUBLISHED 态）
     */
    public void registerQuietly(List<AttributeDef> currentAttributes) {
        try {
            List<IndexFieldDef> subFields = buildSubFields(unionAttributes(currentAttributes));
            if (subFields.isEmpty()) {
                log.info("[index-registrar] 无 is_searchable 属性，跳过动态注册");
                return;
            }
            SearchIndexDef indexDef = buildIndexDefinition(subFields);
            template.reconcileSearchIndex(MetaTableNames.ENTITY_DATA, indexDef);
            log.info("[index-registrar] entity_data_index 动态子字段注册完成: count={}", subFields.size());
        } catch (RuntimeException e) {
            log.warn("[index-registrar] 动态子字段注册失败（不阻断发布，TTL 兜底）: {}", e.getMessage());
        }
    }

    /**
     * 聚合全部已发布实体 schema 的属性并集，并显式并入当前发布 schema
     * （同名 code 以当前发布 schema 为准；防触发 schema 非超集导致重建收窄）。
     *
     * @param currentAttributes 当前发布 schema 属性（可为 null）
     * @return 并集属性列表（保持首次出现序，当前 schema 覆盖同名项）
     */
    List<AttributeDef> unionAttributes(List<AttributeDef> currentAttributes) {
        Map<String, AttributeDef> byCode = new LinkedHashMap<>();
        for (EntitySchema schema : schemaRepository.findAll()) {
            if (!schema.isPublished() || schema.getAttributes() == null) {
                continue;
            }
            for (AttributeDef attribute : schema.getAttributes()) {
                if (attribute.getCode() != null && !attribute.getCode().isBlank()) {
                    byCode.put(attribute.getCode(), attribute);
                }
            }
        }
        if (currentAttributes != null) {
            for (AttributeDef attribute : currentAttributes) {
                if (attribute.getCode() != null && !attribute.getCode().isBlank()) {
                    byCode.put(attribute.getCode(), attribute);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    /**
     * 构建 attributes Nested 子字段清单（可测试暴露）。
     *
     * @param attributes 属性列表
     * @return 子字段定义（仅 is_searchable=true 且类型可映射者）
     */
    List<IndexFieldDef> buildSubFields(List<AttributeDef> attributes) {
        List<IndexFieldDef> subFields = new ArrayList<>();
        if (attributes == null) {
            return subFields;
        }
        for (AttributeDef attribute : attributes) {
            String fieldType = mapIndexType(attribute.getDataType());
            if (!attribute.isSearchable() || fieldType == null) {
                continue;
            }
            subFields.add(new IndexFieldDef(attribute.getCode(), fieldType));
        }
        return subFields;
    }

    /**
     * data_type → 多元索引字段类型映射（design/02 §二）。
     *
     * @param dataType 属性 data_type
     * @return 索引类型；table 与未知类型返回 null（不注册）
     */
    static String mapIndexType(String dataType) {
        if (dataType == null) {
            return null;
        }
        switch (dataType.toLowerCase(Locale.ROOT)) {
            case "string":
                return IndexFieldDef.TYPE_KEYWORD;
            case "integer":
                return IndexFieldDef.TYPE_LONG;
            case "decimal":
                return IndexFieldDef.TYPE_DOUBLE;
            case "boolean":
                return IndexFieldDef.TYPE_BOOLEAN;
            case "date":
            case "datetime":
                // 日期字符串字典序即时间序，KEYWORD 支撑 range 检索
                return IndexFieldDef.TYPE_KEYWORD;
            case "ref":
                return IndexFieldDef.TYPE_KEYWORD;
            default:
                // table 明细子表与未知类型永不注册
                return null;
        }
    }

    /**
     * 完整索引定义：直接从 V1 定义（经 {@link BaselineIndexFields}）读取基线全量列，
     * 再对 attributes/relations Nested 容器注入子字段。机制化派生保证与
     * V1__base-tables.json 始终同构，重建路径不丢列、不再收窄（任务 #9 整改）。
     */
    private SearchIndexDef buildIndexDefinition(List<IndexFieldDef> attributeSubFields) {
        List<IndexFieldDef> fields = new ArrayList<>(BaselineIndexFields.of(
                MetaTableNames.ENTITY_DATA, MetaTableNames.ENTITY_DATA_INDEX));
        for (IndexFieldDef field : fields) {
            if ("attributes".equals(field.getName())) {
                field.setSubFields(attributeSubFields);
            } else if ("relations".equals(field.getName())) {
                // relations 固定子字段（runtime QueryService 实际引用全集：
                // relations[].code / relations[].uuid / relations[].attribute_code）
                field.setSubFields(RelationSubFields.definitions());
            }
        }
        return new SearchIndexDef(MetaTableNames.ENTITY_DATA_INDEX, fields);
    }
}
