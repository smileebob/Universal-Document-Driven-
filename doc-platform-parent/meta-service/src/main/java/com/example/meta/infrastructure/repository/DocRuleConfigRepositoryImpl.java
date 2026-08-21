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
import com.example.meta.domain.doc.DocRuleConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据规则绑定仓储实现（doc_rule_config 单键表，RE-05，保存即生效）。
 */
@Component
public class DocRuleConfigRepositoryImpl implements DocRuleConfig.Repository {

    private static final Logger log = LoggerFactory.getLogger(DocRuleConfigRepositoryImpl.class);

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {
            };

    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public DocRuleConfigRepositoryImpl(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DocRuleConfig> findByDocCode(String docCode) {
        return template.getRow(MetaTableNames.DOC_RULE_CONFIG, pk(docCode)).map(this::toConfig);
    }

    @Override
    public void insert(DocRuleConfig config) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_CREATE_RULES, TsColumnValue.of(toJson(config.getCreateRules())));
        columns.put(MetaTableNames.COL_STATUS_RULES, TsColumnValue.of(toJson(config.getStatusRules())));
        columns.put(MetaTableNames.COL_VERSION, TsColumnValue.of(config.getVersion()));
        if (config.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(config.getUpdateTime()));
        }
        template.putRowIfAbsent(MetaTableNames.DOC_RULE_CONFIG, new TsRow(pk(config.getDocCode()), columns));
    }

    @Override
    public void updateWithVersion(DocRuleConfig config) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put(MetaTableNames.COL_CREATE_RULES,
                TsColumnValue.of(toJson(config.getCreateRules()))));
        updates.add(ColumnUpdate.put(MetaTableNames.COL_STATUS_RULES,
                TsColumnValue.of(toJson(config.getStatusRules()))));
        if (config.getUpdateTime() != null) {
            updates.add(ColumnUpdate.put(MetaTableNames.COL_UPDATE_TIME,
                    TsColumnValue.of(config.getUpdateTime())));
        }
        template.updateWithVersion(MetaTableNames.DOC_RULE_CONFIG, pk(config.getDocCode()),
                config.getVersion(), updates);
    }

    @Override
    public List<DocRuleConfig> findAll() {
        List<TsRow> rows = template.getRange(MetaTableNames.DOC_RULE_CONFIG);
        List<DocRuleConfig> result = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            result.add(toConfig(row));
        }
        return result;
    }

    // ==================== 映射 ====================

    private static TsPrimaryKey pk(String docCode) {
        return TsPrimaryKey.of(MetaTableNames.PK_DOC_CODE, docCode);
    }

    private DocRuleConfig toConfig(TsRow row) {
        DocRuleConfig config = new DocRuleConfig();
        config.setDocCode(String.valueOf(row.getPrimaryKey().get(MetaTableNames.PK_DOC_CODE)));
        config.setCreateRules(parseStringList(row.getString(MetaTableNames.COL_CREATE_RULES)));
        config.setStatusRules(parseMapList(row.getString(MetaTableNames.COL_STATUS_RULES)));
        config.setVersion(row.getLong(MetaTableNames.COL_VERSION) == null
                ? 0L : row.getLong(MetaTableNames.COL_VERSION));
        config.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
        return config;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> value = objectMapper.readValue(json, STRING_LIST_TYPE);
            return value == null ? new ArrayList<>() : value;
        } catch (JsonProcessingException e) {
            log.error("[doc-rule-config] create_rules JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> parseMapList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> value = objectMapper.readValue(json, MAP_LIST_TYPE);
            return value == null ? new ArrayList<>() : value;
        } catch (JsonProcessingException e) {
            log.error("[doc-rule-config] status_rules JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则绑定 JSON 列序列化失败", e);
        }
    }
}
