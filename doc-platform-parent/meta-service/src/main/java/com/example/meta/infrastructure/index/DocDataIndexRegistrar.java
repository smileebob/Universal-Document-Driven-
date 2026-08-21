package com.example.meta.infrastructure.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.infrastructure.repository.MetaTableNames;

/**
 * doc_data 多元索引动态子字段注册器（design/03 §4.3，与 {@link EntityDataIndexRegistrar} 对齐）。
 *
 * <p>单据发布时按 doc_schema attributes 补注册 {@code doc_data_index} 的
 * attributes Nested 子字段：仅 {@code is_searchable=true} 的属性注册，
 * table（明细子表）永不注册；类型映射复用 {@link EntityDataIndexRegistrar#mapIndexType}。
 * relations 固定子字段复用 {@link RelationSubFields}（code/uuid/attribute_code）；
 * related_forms 固定注册 form_type/code/uuid（design/03 §2.4）。</p>
 *
 * <p><b>并集派生防收窄（任务 #10 收口）</b>：reconcile 是「期望集缺失即整体重建」，
 * 若动态子字段只从触发发布的那一个 schema 派生，临时 schema 发布会导致
 * attributes 丢列收窄。现改为聚合全部已发布（PUBLISHED）单据 schema 的
 * is_searchable 属性并集（同名以当前发布 schema 为准，显式并入），任何一次
 * 发布触发的 reconcile 都重建为全量超集。</p>
 *
 * <p>幂等：经 {@link TablestoreTemplate#reconcileSearchIndex} 字段对账注册
 * （描述现有索引 → 求差集 → 已覆盖跳过 / 缺失删除重建；OTS UpdateSearchIndex
 * 不支持增量追加字段，重建为唯一落地路径，权衡见实现类注释）。
 * 注册失败仅 WARN 不阻断发布（缓存 TTL 兜底 + 下次发布重补）。</p>
 */
@Component
public class DocDataIndexRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DocDataIndexRegistrar.class);

    private final TablestoreTemplate template;
    private final DocSchemaDefinition.Repository docSchemaRepository;

    public DocDataIndexRegistrar(TablestoreTemplate template,
                                 DocSchemaDefinition.Repository docSchemaRepository) {
        this.template = template;
        this.docSchemaRepository = docSchemaRepository;
    }

    /**
     * 发布联动注册（尽力而为，失败不抛业务异常）。
     *
     * @param currentAttributes 当前正在发布的 doc_schema 属性列表（Map 形态：
     *                          code/data_type/is_searchable；显式并入并集，
     *                          不依赖其已落 PUBLISHED 态）
     */
    public void registerQuietly(List<Map<String, Object>> currentAttributes) {
        try {
            List<IndexFieldDef> subFields = buildSubFields(unionAttributes(currentAttributes));
            if (subFields.isEmpty()) {
                log.info("[index-registrar] doc_data 无 is_searchable 属性，跳过动态注册");
                return;
            }
            SearchIndexDef indexDef = buildIndexDefinition(subFields);
            template.reconcileSearchIndex(MetaTableNames.DOC_DATA, indexDef);
            log.info("[index-registrar] doc_data_index 动态子字段注册完成: count={}", subFields.size());
        } catch (RuntimeException e) {
            log.warn("[index-registrar] doc_data_index 动态子字段注册失败（不阻断发布，TTL 兜底）: {}",
                    e.getMessage());
        }
    }

    /**
     * 聚合全部已发布单据 schema 的属性并集，并显式并入当前发布 schema
     * （同名 code 以当前发布 schema 为准；防触发 schema 非超集导致重建收窄）。
     *
     * @param currentAttributes 当前发布 schema 属性 Map 列表（可为 null）
     * @return 并集属性列表（保持首次出现序，当前 schema 覆盖同名项）
     */
    List<Map<String, Object>> unionAttributes(List<Map<String, Object>> currentAttributes) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (DocSchemaDefinition definition : docSchemaRepository.findAll()) {
            if (!definition.isPublished() || definition.getAttributes() == null) {
                continue;
            }
            for (Map<String, Object> attribute : definition.getAttributes()) {
                String code = asString(attribute.get("code"));
                if (code != null && !code.isBlank()) {
                    byCode.put(code, attribute);
                }
            }
        }
        if (currentAttributes != null) {
            for (Map<String, Object> attribute : currentAttributes) {
                String code = asString(attribute.get("code"));
                if (code != null && !code.isBlank()) {
                    byCode.put(code, attribute);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    /**
     * 构建 attributes Nested 子字段清单（可测试暴露）。
     *
     * @param attributes doc_schema 属性 Map 列表
     * @return 子字段定义（仅 is_searchable=true 且类型可映射者）
     */
    List<IndexFieldDef> buildSubFields(List<Map<String, Object>> attributes) {
        List<IndexFieldDef> subFields = new ArrayList<>();
        if (attributes == null) {
            return subFields;
        }
        for (Map<String, Object> attribute : attributes) {
            if (!Boolean.TRUE.equals(attribute.get("is_searchable"))) {
                continue;
            }
            String code = asString(attribute.get("code"));
            String fieldType = EntityDataIndexRegistrar.mapIndexType(asString(attribute.get("data_type")));
            if (code == null || code.isBlank() || fieldType == null) {
                continue;
            }
            subFields.add(new IndexFieldDef(code, fieldType));
        }
        return subFields;
    }

    /**
     * 完整索引定义：直接从 V1 定义（经 {@link BaselineIndexFields}）读取基线全量列
     * （含 deleted 逻辑删列），再对 attributes/relations/related_forms Nested 容器
     * 注入子字段。机制化派生保证与 V1__base-tables.json 始终同构，重建路径
     * 不丢列、不再收窄（任务 #9 整改）。
     */
    private SearchIndexDef buildIndexDefinition(List<IndexFieldDef> attributeSubFields) {
        List<IndexFieldDef> fields = new ArrayList<>(BaselineIndexFields.of(
                MetaTableNames.DOC_DATA, MetaTableNames.DOC_DATA_INDEX));
        for (IndexFieldDef field : fields) {
            if ("attributes".equals(field.getName())) {
                field.setSubFields(attributeSubFields);
            } else if ("relations".equals(field.getName())) {
                // relations 固定子字段（runtime QueryService 实际引用全集：
                // relations[].code / relations[].uuid / relations[].attribute_code）
                field.setSubFields(RelationSubFields.definitions());
            } else if ("related_forms".equals(field.getName())) {
                // related_forms 关联表单反查子字段（design/03 §2.4 JSON 结构：form_type/code/uuid）
                field.setSubFields(List.of(
                        new IndexFieldDef("form_type", IndexFieldDef.TYPE_KEYWORD),
                        new IndexFieldDef("code", IndexFieldDef.TYPE_KEYWORD),
                        new IndexFieldDef("uuid", IndexFieldDef.TYPE_KEYWORD)));
            }
        }
        return new SearchIndexDef(MetaTableNames.DOC_DATA_INDEX, fields);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
