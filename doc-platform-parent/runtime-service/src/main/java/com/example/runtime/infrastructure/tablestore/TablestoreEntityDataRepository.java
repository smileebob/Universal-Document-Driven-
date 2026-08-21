package com.example.runtime.infrastructure.tablestore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.model.EntityDataRecord;
import com.example.runtime.domain.port.EntityDataRepository;
import com.example.runtime.domain.port.RefChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实体数据仓储 Tablestore 实现（entity_data 表，design/03 §2.2）。
 *
 * <p>行结构：主键 partition_key={@code {entity_code}_{hash4(uuid)}} + uuid；
 * 属性列 entity_code/entity_type/status/version/creator/create_dept/
 * create_time/update_time + attributes/relations（JSON 字符串列，design/03 §二）。
 * 同时实现 {@link RefChecker}：ref 校验走主键 GetRow 点查（TS-03 强一致）。</p>
 */
@Component
public class TablestoreEntityDataRepository implements EntityDataRepository, RefChecker {

    static final String TABLE = "entity_data";
    static final String INDEX = "entity_data_index";

    private static final Logger log = LoggerFactory.getLogger(TablestoreEntityDataRepository.class);

    /** status != 0 强制过滤取值（1=有效 / 100=暂存） */
    private static final List<TsColumnValue> ACTIVE_STATUS =
            List.of(TsColumnValue.of(1L), TsColumnValue.of(100L));

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public TablestoreEntityDataRepository(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    // ==================== 写路径 ====================

    @Override
    public void insert(EntityDataRecord record) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("entity_code", TsColumnValue.of(record.getEntityCode()));
        columns.put("entity_type", TsColumnValue.of(record.getEntityType()));
        columns.put("status", TsColumnValue.of((long) record.getStatus()));
        columns.put("version", TsColumnValue.of(record.getVersion()));
        columns.put("attributes", TsColumnValue.of(toJson(record.getAttributes())));
        columns.put("relations", TsColumnValue.of(toJson(record.getRelations())));
        putIfNotNull(columns, "creator", record.getCreator());
        putIfNotNull(columns, "create_dept", record.getCreateDept());
        columns.put("create_time", TsColumnValue.of(record.getCreateTime()));
        columns.put("update_time", TsColumnValue.of(record.getUpdateTime()));
        template.putRowIfAbsent(TABLE, new TsRow(dataPk(record.getEntityCode(), record.getUuid()), columns));
    }

    @Override
    public boolean updateWithVersion(String entityCode, String uuid, long expectVersion,
                                     Map<String, Object> attributes, List<Map<String, Object>> relations,
                                     long updateTime) {
        List<ColumnUpdate> updates = List.of(
                ColumnUpdate.put("attributes", TsColumnValue.of(toJson(attributes))),
                ColumnUpdate.put("relations", TsColumnValue.of(toJson(relations))),
                ColumnUpdate.put("update_time", TsColumnValue.of(updateTime)));
        try {
            template.updateWithVersion(TABLE, dataPk(entityCode, uuid), expectVersion, updates);
            return true;
        } catch (TsConditionCheckException e) {
            // version 冲突：调用方按 40901 处理
            return false;
        }
    }

    @Override
    public boolean logicalDelete(String entityCode, String uuid, long expectVersion, long updateTime) {
        List<ColumnUpdate> updates = List.of(
                ColumnUpdate.put("status", TsColumnValue.of((long) EntityDataRecord.STATUS_DELETED)),
                ColumnUpdate.put("update_time", TsColumnValue.of(updateTime)));
        try {
            template.updateWithVersion(TABLE, dataPk(entityCode, uuid), expectVersion, updates);
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    // ==================== 读路径 ====================

    @Override
    public Optional<EntityDataRecord> findByKey(String entityCode, String uuid) {
        return template.getRow(TABLE, dataPk(entityCode, uuid)).map(this::mapRow);
    }

    @Override
    public boolean isActiveData(String entityCode, String uuid) {
        return findByKey(entityCode, uuid).map(record -> !record.isDeleted()).orElse(false);
    }

    @Override
    public long countReferencing(String targetUuid) {
        QuerySpec spec = QuerySpec.builder()
                .nestedTerm("relations", "uuid", TsColumnValue.of(targetUuid))
                .terms("status", ACTIVE_STATUS)
                .limit(1).getTotalCount(true).build();
        return template.search(TABLE, INDEX, spec).getTotalCount();
    }

    @Override
    public long countByRelationsCode(String targetCode) {
        QuerySpec spec = QuerySpec.builder()
                .nestedTerm("relations", "code", TsColumnValue.of(targetCode))
                .terms("status", ACTIVE_STATUS)
                .limit(1).getTotalCount(true).build();
        return template.search(TABLE, INDEX, spec).getTotalCount();
    }

    @Override
    public long countActiveByEntityCode(String entityCode) {
        QuerySpec spec = QuerySpec.builder()
                .term("entity_code", TsColumnValue.of(entityCode))
                .terms("status", ACTIVE_STATUS)
                .limit(1).getTotalCount(true).build();
        return template.search(TABLE, INDEX, spec).getTotalCount();
    }

    @Override
    public SearchResult searchActiveRows(QuerySpec spec) {
        return template.search(TABLE, INDEX, spec);
    }

    // ==================== 行映射 ====================

    /** 主键构造：partition_key + uuid */
    static TsPrimaryKey dataPk(String entityCode, String uuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(entityCode, uuid))
                .add("uuid", uuid)
                .build();
    }

    private EntityDataRecord mapRow(TsRow row) {
        String uuid = String.valueOf(row.getPrimaryKey().get("uuid"));
        Long status = row.getLong("status");
        Long version = row.getLong("version");
        Long createTime = row.getLong("create_time");
        Long updateTime = row.getLong("update_time");
        return new EntityDataRecord(uuid, row.getString("entity_code"), row.getString("entity_type"),
                status == null ? EntityDataRecord.STATUS_DELETED : status.intValue(),
                version == null ? 1L : version,
                parseJsonMap(row.getString("attributes")),
                parseJsonList(row.getString("relations")),
                row.getString("creator"), row.getString("create_dept"),
                createTime == null ? 0L : createTime,
                updateTime == null ? 0L : updateTime);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, LinkedHashMap.class);
            return map;
        } catch (JsonProcessingException e) {
            log.warn("[entity-data] attributes 列反序列化失败", e);
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            log.warn("[entity-data] relations 列反序列化失败", e);
            return new ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("属性/关系 JSON 序列化失败", e);
        }
    }

    private void putIfNotNull(Map<String, TsColumnValue> columns, String name, String value) {
        if (value != null) {
            columns.put(name, TsColumnValue.of(value));
        }
    }
}
