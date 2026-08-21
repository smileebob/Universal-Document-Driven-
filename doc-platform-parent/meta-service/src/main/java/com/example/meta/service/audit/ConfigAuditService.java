package com.example.meta.service.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.meta.web.RequestUserContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 配置写入审计服务（operation=config，RD-12 / design/03 §2.10 / design/05 §6.2）。
 *
 * <p>实体/单据定义 CRUD 与发布停用、状态机/规则绑定/事务配置/菜单挂载等配置变更
 * 成功后记审计；写入 audit_log（分区键 {@code log_{hash6(log_id)}}），best-effort
 * 语义：写失败仅 WARN，不影响配置主流程。</p>
 *
 * <p>前后值快照（阶段四补齐）：before_value/after_value 均为自描述 JSON
 * {@code {"action":..,"snapshot":{..}}}，供前端 JSON Diff；create 无 before、
 * delete 无 after。操作人经 {@link RequestUserContext} 取 X-User-Id（缺省 system）。</p>
 */
@Service
public class ConfigAuditService {

    private static final Logger log = LoggerFactory.getLogger(ConfigAuditService.class);

    private static final String TABLE_AUDIT_LOG = "audit_log";

    private final TablestoreTemplate template;
    private final ObjectMapper objectMapper;

    public ConfigAuditService(TablestoreTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一条配置变更审计（operation=config，含前后值快照）。
     *
     * @param targetType 配置域（entity_schema/doc_schema/state_machine/
     *                   doc_rule_config/tx_config/rule_lib/menu_config）
     * @param targetCode 配置对象编码（实体/单据编码或规则 uuid）
     * @param action     动作（create/update/publish/disable/delete/save/mount）
     * @param before     变更前快照对象（null=无，如 create）
     * @param after      变更后快照对象（null=无，如 delete）
     */
    public void recordConfig(String targetType, String targetCode, String action,
                             Object before, Object after) {
        try {
            String logId = UUID.randomUUID().toString();
            Map<String, TsColumnValue> columns = new LinkedHashMap<>();
            columns.put("operation", TsColumnValue.of("config"));
            columns.put("target_type", TsColumnValue.of(targetType));
            columns.put("target_code", TsColumnValue.of(targetCode));
            columns.put("operator", TsColumnValue.of(RequestUserContext.operator()));
            columns.put("operate_time", TsColumnValue.of(System.currentTimeMillis()));
            String beforeJson = snapshotJson(action, before);
            if (beforeJson != null) {
                columns.put("before_value", TsColumnValue.of(beforeJson));
            }
            String afterJson = snapshotJson(action, after);
            if (afterJson != null) {
                columns.put("after_value", TsColumnValue.of(afterJson));
            }
            template.putRow(TABLE_AUDIT_LOG, new TsRow(TsPrimaryKey.builder()
                    .add("partition_key", PartitionKeys.logKey(logId))
                    .add("log_id", logId)
                    .build(), columns));
        } catch (RuntimeException e) {
            log.warn("[config-audit] 配置审计写入失败（不影响主流程）: {}/{} action={}",
                    targetType, targetCode, action, e);
        }
    }

    /** 快照包装：{"action":..,"snapshot":..}；对象为 null 返回 null（不写该列） */
    private String snapshotJson(String action, Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("action", action);
            wrapper.put("snapshot", snapshot);
            return objectMapper.writeValueAsString(wrapper);
        } catch (JsonProcessingException e) {
            log.warn("[config-audit] 快照序列化失败，降级文本快照: action={}", action);
            return "{\"action\":\"" + action + "\",\"snapshot\":\"" + snapshot + "\"}";
        }
    }
}
