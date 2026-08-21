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
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.meta.domain.doc.StateMachineConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 状态机配置仓储实现（state_machine 单键表，SM-01/02，保存即生效）。
 */
@Component
public class StateMachineRepositoryImpl implements StateMachineConfig.Repository {

    private static final Logger log = LoggerFactory.getLogger(StateMachineRepositoryImpl.class);

    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public StateMachineRepositoryImpl(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StateMachineConfig> findByDocCode(String docCode) {
        return template.getRow(MetaTableNames.STATE_MACHINE, pk(docCode)).map(this::toConfig);
    }

    @Override
    public void insert(StateMachineConfig config) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_STATES, TsColumnValue.of(toJson(config.getStates())));
        columns.put(MetaTableNames.COL_TRANSITIONS, TsColumnValue.of(toJson(config.getTransitions())));
        columns.put(MetaTableNames.COL_VERSION, TsColumnValue.of(config.getVersion()));
        if (config.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(config.getUpdateTime()));
        }
        template.putRowIfAbsent(MetaTableNames.STATE_MACHINE, new TsRow(pk(config.getDocCode()), columns));
    }

    @Override
    public void updateWithVersion(StateMachineConfig config) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put(MetaTableNames.COL_STATES, TsColumnValue.of(toJson(config.getStates()))));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_TRANSITIONS,
                TsColumnValue.of(toJson(config.getTransitions()))));
        if (config.getUpdateTime() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_UPDATE_TIME,
                    TsColumnValue.of(config.getUpdateTime())));
        }
        template.updateWithVersion(MetaTableNames.STATE_MACHINE, pk(config.getDocCode()),
                config.getVersion(), updates);
    }

    // ==================== 映射 ====================

    private static TsPrimaryKey pk(String docCode) {
        return TsPrimaryKey.of(MetaTableNames.PK_DOC_CODE, docCode);
    }

    private StateMachineConfig toConfig(TsRow row) {
        StateMachineConfig config = new StateMachineConfig();
        config.setDocCode(String.valueOf(row.getPrimaryKey().get(MetaTableNames.PK_DOC_CODE)));
        config.setStates(parseMapList(row.getString(MetaTableNames.COL_STATES), "states"));
        config.setTransitions(parseMapList(row.getString(MetaTableNames.COL_TRANSITIONS), "transitions"));
        config.setVersion(row.getLong(MetaTableNames.COL_VERSION) == null
                ? 0L : row.getLong(MetaTableNames.COL_VERSION));
        config.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
        return config;
    }

    private List<Map<String, Object>> parseMapList(String json, String column) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> value = objectMapper.readValue(json, MAP_LIST_TYPE);
            return value == null ? new ArrayList<>() : value;
        } catch (JsonProcessingException e) {
            log.error("[state-machine] {} JSON 解析失败: {}", column, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(List<Map<String, Object>> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("状态机 JSON 列序列化失败", e);
        }
    }
}
