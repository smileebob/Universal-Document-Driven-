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
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntitySchemaRepository;
import com.example.meta.domain.schema.EntityStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实体定义仓储实现（entity_schema 单键表，仅经 TablestoreTemplate 门面，F-06）。
 */
@Component
public class EntitySchemaRepositoryImpl implements EntitySchemaRepository {

    private static final Logger log = LoggerFactory.getLogger(EntitySchemaRepositoryImpl.class);

    private static final TypeReference<List<AttributeDef>> ATTRIBUTES_TYPE =
            new TypeReference<>() {
            };

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public EntitySchemaRepositoryImpl(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<EntitySchema> findByCode(String code) {
        return template.getRow(MetaTableNames.ENTITY_SCHEMA, pk(code)).map(this::toSchema);
    }

    @Override
    public void insert(EntitySchema schema) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_ENTITY_NAME, TsColumnValue.of(schema.getName()));
        putIfPresent(columns, MetaTableNames.COL_ENTITY_TYPE, schema.getEntityType());
        putIfPresent(columns, MetaTableNames.COL_DESCRIPTION, schema.getDescription());
        columns.put(MetaTableNames.COL_ATTRIBUTES, TsColumnValue.of(toJson(schema.getAttributes())));
        columns.put(MetaTableNames.COL_STATUS, TsColumnValue.of(schema.getStatus().getStorageValue()));
        putIfPresent(columns, MetaTableNames.COL_CREATOR, schema.getCreator());
        putIfPresent(columns, MetaTableNames.COL_CREATE_DEPT, schema.getCreateDept());
        if (schema.getCreateTime() != null) {
            columns.put(MetaTableNames.COL_CREATE_TIME, TsColumnValue.of(schema.getCreateTime()));
        }
        if (schema.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(schema.getUpdateTime()));
        }
        columns.put(MetaTableNames.COL_VERSION, TsColumnValue.of(schema.getVersion()));
        // EXPECT_NOT_EXIST 幂等建行；重复编码抛 TsConditionCheckException（上层映射 40006）
        template.putRowIfAbsent(MetaTableNames.ENTITY_SCHEMA, new TsRow(pk(schema.getCode()), columns));
    }

    @Override
    public void updateWithVersion(EntitySchema schema) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put(MetaTableNames.COL_ENTITY_NAME, TsColumnValue.of(schema.getName())));
        updates.add(textColumn(MetaTableNames.COL_ENTITY_TYPE, schema.getEntityType()));
        updates.add(textColumn(MetaTableNames.COL_DESCRIPTION, schema.getDescription()));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_ATTRIBUTES,
                TsColumnValue.of(toJson(schema.getAttributes()))));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_STATUS,
                TsColumnValue.of(schema.getStatus().getStorageValue())));
        if (schema.getPublishedAt() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_PUBLISHED_AT,
                    TsColumnValue.of(schema.getPublishedAt())));
        } else {
            updates.add(ColumnUpdate.delete(MetaTableNames.COL_PUBLISHED_AT));
        }
        if (schema.getUpdateTime() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_UPDATE_TIME,
                    TsColumnValue.of(schema.getUpdateTime())));
        }
        // version CAS（TS-04）：期望行内 version == 读取快照，成功后自动 +1
        template.updateWithVersion(MetaTableNames.ENTITY_SCHEMA, pk(schema.getCode()),
                schema.getVersion(), updates);
    }

    @Override
    public void deleteByCode(String code) {
        template.deleteRow(MetaTableNames.ENTITY_SCHEMA, pk(code));
    }

    @Override
    public void deleteByCodeIfDraft(String code, long expectVersion) {
        // 条件删除（评审修复 Major-6）：EXPECT_EXIST + version 列等值（读取快照 CAS），
        // 并发修改/删除时 OTSConditionCheckFail → TsConditionCheckException → 40901
        template.deleteRow(MetaTableNames.ENTITY_SCHEMA, pk(code),
                RowCondition.expectExistAndColumnEq(MetaTableNames.COL_VERSION,
                        TsColumnValue.of(expectVersion)));
    }

    @Override
    public List<EntitySchema> findAll() {
        List<TsRow> rows = template.getRange(MetaTableNames.ENTITY_SCHEMA);
        List<EntitySchema> result = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            result.add(toSchema(row));
        }
        return result;
    }

    // ==================== 映射 ====================

    private static TsPrimaryKey pk(String code) {
        return TsPrimaryKey.of(MetaTableNames.PK_ENTITY_CODE, code);
    }

    private EntitySchema toSchema(TsRow row) {
        EntitySchema schema = new EntitySchema();
        schema.setCode(String.valueOf(row.getPrimaryKey().get(MetaTableNames.PK_ENTITY_CODE)));
        schema.setName(row.getString(MetaTableNames.COL_ENTITY_NAME));
        schema.setEntityType(row.getString(MetaTableNames.COL_ENTITY_TYPE));
        schema.setDescription(row.getString(MetaTableNames.COL_DESCRIPTION));
        schema.setStatus(EntityStatus.fromStorage(
                row.getLong(MetaTableNames.COL_STATUS) == null
                        ? EntityStatus.DRAFT.getStorageValue()
                        : row.getLong(MetaTableNames.COL_STATUS)));
        schema.setVersion(row.getLong(MetaTableNames.COL_VERSION) == null
                ? 0L : row.getLong(MetaTableNames.COL_VERSION));
        schema.setPublishedAt(row.getLong(MetaTableNames.COL_PUBLISHED_AT));
        schema.setCreator(row.getString(MetaTableNames.COL_CREATOR));
        schema.setCreateDept(row.getString(MetaTableNames.COL_CREATE_DEPT));
        schema.setCreateTime(row.getLong(MetaTableNames.COL_CREATE_TIME));
        schema.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
        schema.setAttributes(parseAttributes(row.getString(MetaTableNames.COL_ATTRIBUTES)));
        return schema;
    }

    private List<AttributeDef> parseAttributes(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<AttributeDef> attributes = objectMapper.readValue(json, ATTRIBUTES_TYPE);
            return attributes == null ? new ArrayList<>() : attributes;
        } catch (JsonProcessingException e) {
            // 定义数据损坏：记录 ERROR 返回空属性，发布完整性校验会拦截再次发布
            log.error("[entity-schema] attributes JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(List<AttributeDef> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes == null ? List.of() : attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("attributes 序列化失败", e);
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
