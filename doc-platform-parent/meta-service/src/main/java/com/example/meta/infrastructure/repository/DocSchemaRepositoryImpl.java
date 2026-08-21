package com.example.meta.infrastructure.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据定义仓储实现（doc_schema 单键表，DC-01~05，仅经 TablestoreTemplate 门面，F-06）。
 */
@Component
public class DocSchemaRepositoryImpl implements DocSchemaDefinition.Repository {

    private static final Logger log = LoggerFactory.getLogger(DocSchemaRepositoryImpl.class);

    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public DocSchemaRepositoryImpl(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DocSchemaDefinition> findByCode(String code) {
        return template.getRow(MetaTableNames.DOC_SCHEMA, pk(code)).map(this::toDefinition);
    }

    @Override
    public void insert(DocSchemaDefinition definition) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_DOC_UUID, TsColumnValue.of(definition.getUuid()));
        columns.put(MetaTableNames.COL_DOC_NAME, TsColumnValue.of(definition.getName()));
        putIfPresent(columns, MetaTableNames.COL_DOC_TYPE, definition.getDocType());
        putIfPresent(columns, MetaTableNames.COL_DESCRIPTION, definition.getDescription());
        columns.put(MetaTableNames.COL_ATTRIBUTES, TsColumnValue.of(toJson(definition.getAttributes())));
        columns.put(MetaTableNames.COL_STATUS_DEFS, TsColumnValue.of(toJson(definition.getStatusDefs())));
        columns.put(MetaTableNames.COL_STATUS, TsColumnValue.of(definition.getStatus()));
        putIfPresent(columns, MetaTableNames.COL_CREATOR, definition.getCreator());
        if (definition.getCreateTime() != null) {
            columns.put(MetaTableNames.COL_CREATE_TIME, TsColumnValue.of(definition.getCreateTime()));
        }
        if (definition.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(definition.getUpdateTime()));
        }
        columns.put(MetaTableNames.COL_VERSION, TsColumnValue.of(definition.getVersion()));
        // EXPECT_NOT_EXIST 幂等建行；重复编码抛 TsConditionCheckException（上层映射 40006）
        template.putRowIfAbsent(MetaTableNames.DOC_SCHEMA, new TsRow(pk(definition.getCode()), columns));
    }

    @Override
    public void updateWithVersion(DocSchemaDefinition definition) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put(MetaTableNames.COL_DOC_NAME, TsColumnValue.of(definition.getName())));
        updates.add(textColumn(MetaTableNames.COL_DOC_TYPE, definition.getDocType()));
        updates.add(textColumn(MetaTableNames.COL_DESCRIPTION, definition.getDescription()));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_ATTRIBUTES,
                TsColumnValue.of(toJson(definition.getAttributes()))));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_STATUS_DEFS,
                TsColumnValue.of(toJson(definition.getStatusDefs()))));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_STATUS, TsColumnValue.of(definition.getStatus())));
        if (definition.getPublishedAt() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_PUBLISHED_AT,
                    TsColumnValue.of(definition.getPublishedAt())));
        } else {
            updates.add(ColumnUpdate.delete(MetaTableNames.COL_PUBLISHED_AT));
        }
        if (definition.getUpdateTime() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_UPDATE_TIME,
                    TsColumnValue.of(definition.getUpdateTime())));
        }
        // version CAS（TS-04）：期望行内 version == 读取快照，成功后自动 +1
        template.updateWithVersion(MetaTableNames.DOC_SCHEMA, pk(definition.getCode()),
                definition.getVersion(), updates);
    }

    @Override
    public void deleteByCodeIfDraft(String code, long expectVersion) {
        // 条件删除：EXPECT_EXIST + version 列等值（读取快照 CAS），
        // 并发修改/删除时 OTSConditionCheckFail → TsConditionCheckException → 40901
        template.deleteRow(MetaTableNames.DOC_SCHEMA, pk(code),
                RowCondition.expectExistAndColumnEq(MetaTableNames.COL_VERSION,
                        TsColumnValue.of(expectVersion)));
    }

    @Override
    public List<DocSchemaDefinition> findAll() {
        List<TsRow> rows = template.getRange(MetaTableNames.DOC_SCHEMA);
        List<DocSchemaDefinition> result = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            result.add(toDefinition(row));
        }
        return result;
    }

    // ==================== 映射 ====================

    private static TsPrimaryKey pk(String code) {
        return TsPrimaryKey.of(MetaTableNames.PK_DOC_CODE, code);
    }

    private DocSchemaDefinition toDefinition(TsRow row) {
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setCode(String.valueOf(row.getPrimaryKey().get(MetaTableNames.PK_DOC_CODE)));
        definition.setUuid(row.getString(MetaTableNames.COL_DOC_UUID));
        definition.setName(row.getString(MetaTableNames.COL_DOC_NAME));
        definition.setDocType(row.getString(MetaTableNames.COL_DOC_TYPE));
        definition.setDescription(row.getString(MetaTableNames.COL_DESCRIPTION));
        definition.setStatus(row.getLong(MetaTableNames.COL_STATUS) == null
                ? DocSchemaDefinition.STATUS_DRAFT : row.getLong(MetaTableNames.COL_STATUS));
        definition.setVersion(row.getLong(MetaTableNames.COL_VERSION) == null
                ? 0L : row.getLong(MetaTableNames.COL_VERSION));
        definition.setCreator(row.getString(MetaTableNames.COL_CREATOR));
        definition.setCreateTime(row.getLong(MetaTableNames.COL_CREATE_TIME));
        definition.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
        definition.setPublishedAt(row.getLong(MetaTableNames.COL_PUBLISHED_AT));
        definition.setAttributes(parseMapList(row.getString(MetaTableNames.COL_ATTRIBUTES), "attributes"));
        definition.setStatusDefs(parseMapList(row.getString(MetaTableNames.COL_STATUS_DEFS), "status_defs"));
        return definition;
    }

    private List<Map<String, Object>> parseMapList(String json, String column) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> value = objectMapper.readValue(json, MAP_LIST_TYPE);
            return value == null ? new ArrayList<>() : value;
        } catch (JsonProcessingException e) {
            // 定义数据损坏：记录 ERROR 返回空列表，发布完整性校验会拦截再次发布
            log.error("[doc-schema] {} JSON 解析失败: {}", column, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(List<Map<String, Object>> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("单据定义 JSON 列序列化失败", e);
        }
    }

    private static void putIfPresent(Map<String, TsColumnValue> columns, String name, String value) {
        if (value != null) {
            columns.put(name, TsColumnValue.of(value));
        }
    }

    private static ColumnUpdate textColumn(String name, String value) {
        return value == null ? ColumnUpdate.delete(name) : ColumnUpdate.put(name, TsColumnValue.of(value));
    }
}
