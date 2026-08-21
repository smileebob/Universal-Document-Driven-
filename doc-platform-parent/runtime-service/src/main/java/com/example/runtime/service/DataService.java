package com.example.runtime.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.AttributeType;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.model.EntityDataRecord;
import com.example.runtime.domain.model.EntitySchema;
import com.example.runtime.domain.model.FieldError;
import com.example.runtime.domain.port.EntityDataRepository;
import com.example.runtime.domain.port.RefChecker;
import com.example.runtime.domain.port.SchemaGateway;
import com.example.runtime.domain.validation.DataValidationChain;
import com.example.runtime.domain.validation.RefValidator;
import com.example.runtime.domain.validation.RuleValidator;
import com.example.runtime.domain.validation.ValidationContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实体数据 CRUD 服务（RD-01~04）。
 *
 * <p>写路径编排（design/03 §五 + leader 裁决）：</p>
 * <ol>
 *   <li>新增：schema 回源 → 校验链①②③⑤ → 唯一占位④ → 写主行（EXPECT_NOT_EXIST）→
 *       失败回滚占位 → 审计旁路；</li>
 *   <li>修改：读存量（主键点查）→ editable 兜底 → 合并 → 校验链 → 占新值 →
 *       version CAS 更新（失败回滚新占位 40901）→ 成功后释放旧值 → 审计旁路；</li>
 *   <li>删除：引用保护（relations 反查）→ status 置 0 的 version CAS；
 *       唯一占位按 design/03 §5.6 <b>保留不释放</b> → 审计旁路；</li>
 *   <li>详情：主键点查 + REF 属性展示值展开（RD-04）。</li>
 * </ol>
 */
@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);

    /** 审计目标类型：实体数据 */
    private static final String TARGET_TYPE_ENTITY = "entity_data";

    private final SchemaGateway schemaGateway;
    private final EntityDataRepository dataRepository;
    private final UniqueConstraintService uniqueConstraintService;
    private final AuditService auditService;
    private final DataValidationChain validationChain;
    private final ObjectMapper objectMapper;

    public DataService(SchemaGateway schemaGateway, EntityDataRepository dataRepository,
                       RefChecker refChecker, UniqueConstraintService uniqueConstraintService,
                       AuditService auditService, ObjectMapper objectMapper) {
        this.schemaGateway = schemaGateway;
        this.dataRepository = dataRepository;
        this.uniqueConstraintService = uniqueConstraintService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        // 校验链①②③⑤；④唯一占位为强一致写流程，由本服务编排
        this.validationChain = DataValidationChain.standard(
                new RefValidator(refChecker), new RuleValidator.NoopRuleValidator());
    }

    // ==================== RD-01 新增 ====================

    /**
     * 新增实体数据。
     *
     * @param entityCode 实体编码
     * @param rawAttributes 提交属性值
     * @param operator   操作人（X-User-Id 权威值）
     * @param createDept 创建部门
     * @param ip         来源 IP（X-Forwarded-For 首段）
     * @param traceId    链路 ID
     * @return {@code {"uuid": ..., "status": 1}}
     */
    public Map<String, Object> create(String entityCode, Map<String, Object> rawAttributes,
                                      String operator, String createDept, String ip, String traceId) {
        EntitySchema schema = schemaGateway.getEntitySchema(entityCode);
        Map<String, Object> attributes = prepareAttributes(schema, rawAttributes);
        validationChain.validate(new ValidationContext(schema, attributes, false, null));

        String uuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        List<UniqueConstraintService.Reserved> reserved =
                uniqueConstraintService.reserveForCreate(schema, attributes, uuid);
        EntityDataRecord record = new EntityDataRecord(uuid, entityCode, EntityDataRecord.TYPE_ENTITY,
                EntityDataRecord.STATUS_ACTIVE, 1L, attributes, buildRelations(schema, attributes),
                operator, createDept, now, now);
        try {
            dataRepository.insert(record);
        } catch (TsConditionCheckException e) {
            // 主行写入失败：回滚唯一占位后按冲突返回
            uniqueConstraintService.releaseAll(entityCode, reserved);
            throw new BizException(ErrorCode.STATUS_CONFLICT, ErrorCode.STATUS_CONFLICT.getDefaultMessage());
        }
        auditService.recordAsync(auditEntry("create", entityCode, uuid, operator, ip, traceId,
                null, toJson(attributes)));
        return Map.of("uuid", uuid, "status", EntityDataRecord.STATUS_ACTIVE);
    }

    // ==================== RD-02 修改 ====================

    /**
     * 修改实体数据（version 乐观锁 + unique 换值）。
     *
     * @param entityCode    实体编码
     * @param uuid          数据唯一标识
     * @param rawAttributes 本次修改的属性值（与存量合并）
     * @param expectVersion 读取时的 version
     * @param operator      操作人
     * @param ip            来源 IP
     * @param traceId       链路 ID
     * @return {@code {"uuid","version","status"}}
     */
    public Map<String, Object> update(String entityCode, String uuid, Map<String, Object> rawAttributes,
                                      long expectVersion, String operator, String ip, String traceId) {
        EntitySchema schema = schemaGateway.getEntitySchema(entityCode);
        EntityDataRecord existing = loadActive(entityCode, uuid);
        Map<String, Object> submitted = rawAttributes == null ? Map.of() : rawAttributes;
        checkUnknownAttributes(schema, submitted);
        checkEditable(schema, existing.getAttributes(), submitted);

        // 合并：存量值 + 本次提交覆盖
        Map<String, Object> merged = new LinkedHashMap<>(existing.getAttributes());
        merged.putAll(submitted);
        validationChain.validate(new ValidationContext(schema, merged, true, uuid));

        List<UniqueConstraintService.Reserved> reserved =
                uniqueConstraintService.reserveChanged(schema, existing.getAttributes(), merged, uuid);
        long now = System.currentTimeMillis();
        boolean ok = dataRepository.updateWithVersion(entityCode, uuid, expectVersion,
                merged, buildRelations(schema, merged), now);
        if (!ok) {
            uniqueConstraintService.releaseAll(entityCode, reserved);
            throw new BizException(ErrorCode.STATUS_CONFLICT, ErrorCode.STATUS_CONFLICT.getDefaultMessage());
        }
        // 主行 CAS 成功后释放旧值占位（换值流程第三步）
        uniqueConstraintService.releaseChangedOldValues(schema, existing.getAttributes(), merged);
        auditService.recordAsync(auditEntry("update", entityCode, uuid, operator, ip, traceId,
                toJson(existing.getAttributes()), toJson(merged)));
        return Map.of("uuid", uuid, "version", expectVersion + 1, "status", existing.getStatus());
    }

    // ==================== RD-03 逻辑删除 ====================

    /**
     * 逻辑删除实体数据（status 置 0；唯一占位保留不释放，design/03 §5.6）。
     *
     * @param entityCode 实体编码
     * @param uuid       数据唯一标识
     * @param operator   操作人
     * @param ip         来源 IP
     * @param traceId    链路 ID
     */
    public void delete(String entityCode, String uuid, String operator, String ip, String traceId) {
        EntityDataRecord existing = loadActive(entityCode, uuid);
        long referencing = dataRepository.countReferencing(uuid);
        if (referencing > 0) {
            throw new BizException(ErrorCode.REF_PROTECTED,
                    "该数据正被 " + referencing + " 条记录引用，无法删除");
        }
        boolean ok = dataRepository.logicalDelete(entityCode, uuid,
                existing.getVersion(), System.currentTimeMillis());
        if (!ok) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, ErrorCode.STATUS_CONFLICT.getDefaultMessage());
        }
        auditService.recordAsync(auditEntry("delete", entityCode, uuid, operator, ip, traceId,
                toJson(existing.getAttributes()), null));
    }

    // ==================== RD-04 详情 ====================

    /**
     * 查询数据详情（主键点查 + REF 展示值展开）。
     *
     * @param entityCode 实体编码
     * @param uuid       数据唯一标识
     * @return 详情投影（含 ref_display）
     */
    public Map<String, Object> detail(String entityCode, String uuid) {
        EntityDataRecord record = loadActive(entityCode, uuid);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uuid", record.getUuid());
        view.put("entity_code", record.getEntityCode());
        view.put("entity_type", record.getEntityType());
        view.put("status", record.getStatus());
        view.put("version", record.getVersion());
        view.put("attributes", record.getAttributes());
        view.put("relations", record.getRelations());
        view.put("creator", record.getCreator());
        view.put("create_dept", record.getCreateDept());
        view.put("create_time", record.getCreateTime());
        view.put("update_time", record.getUpdateTime());
        view.put("ref_display", expandRefDisplay(record));
        return view;
    }

    // ==================== 内部步骤 ====================

    /** 主键点查加载未删除数据（写后读强一致，TS-03） */
    private EntityDataRecord loadActive(String entityCode, String uuid) {
        EntityDataRecord record = dataRepository.findByKey(entityCode, uuid)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        ErrorCode.NOT_FOUND.getDefaultMessage()));
        return record;
    }

    /** 提交值预处理：未知属性拒绝 + 默认值回填 */
    private Map<String, Object> prepareAttributes(EntitySchema schema, Map<String, Object> rawAttributes) {
        Map<String, Object> submitted = rawAttributes == null ? Map.of() : rawAttributes;
        checkUnknownAttributes(schema, submitted);
        Map<String, Object> attributes = new LinkedHashMap<>(submitted);
        for (AttributeDef def : schema.getAttributes()) {
            if (attributes.get(def.getCode()) == null && def.getDefaultValue() != null) {
                attributes.put(def.getCode(), def.getDefaultValue());
            }
        }
        return attributes;
    }

    /** 未定义属性拒绝（schema 驱动，40002 field_errors） */
    private void checkUnknownAttributes(EntitySchema schema, Map<String, Object> submitted) {
        List<FieldError> errors = new ArrayList<>();
        for (String code : submitted.keySet()) {
            if (schema.findAttribute(code).isEmpty()) {
                errors.add(new FieldError(code, code, "「" + code + "」未在实体定义中声明"));
            }
        }
        if (!errors.isEmpty()) {
            throw DataValidationChain.toException(ErrorCode.TYPE_INVALID, errors);
        }
    }

    /** editable=false 属性拒绝修改（前端置灰的后端兜底，RD-02，40007） */
    private void checkEditable(EntitySchema schema, Map<String, Object> oldAttributes,
                               Map<String, Object> submitted) {
        List<FieldError> errors = new ArrayList<>();
        for (AttributeDef def : schema.getAttributes()) {
            if (def.isEditable() || !submitted.containsKey(def.getCode())) {
                continue;
            }
            if (!Objects.equals(submitted.get(def.getCode()), oldAttributes.get(def.getCode()))) {
                errors.add(new FieldError(def.getCode(), def.getName(),
                        "「" + def.getName() + "」不可修改"));
            }
        }
        if (!errors.isEmpty()) {
            throw DataValidationChain.toException(ErrorCode.NOT_EDITABLE, errors);
        }
    }

    /** REF 属性 → relations 投影（引用保护反查依据，PRD 11 §3.2 结构） */
    private List<Map<String, Object>> buildRelations(EntitySchema schema, Map<String, Object> attributes) {
        List<Map<String, Object>> relations = new ArrayList<>();
        for (AttributeDef def : schema.getAttributes()) {
            if (def.getType() != AttributeType.REF) {
                continue;
            }
            Object value = attributes.get(def.getCode());
            if (value == null || def.getRefEntityCode() == null) {
                continue;
            }
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("type", "biz_entity");
            relation.put("code", def.getRefEntityCode());
            relation.put("uuid", String.valueOf(value));
            relations.add(relation);
        }
        return relations;
    }

    /** 详情 REF 展示值展开（目标定义/目标数据缺失时跳过该属性，不阻断详情） */
    private Map<String, Object> expandRefDisplay(EntityDataRecord record) {
        EntitySchema schema;
        try {
            schema = schemaGateway.getEntitySchema(record.getEntityCode());
        } catch (BizException e) {
            log.warn("[data] 详情 ref 展开跳过（定义回源失败）: entityCode={}", record.getEntityCode());
            return Map.of();
        }
        Map<String, Object> refDisplay = new LinkedHashMap<>();
        for (AttributeDef def : schema.getAttributes()) {
            if (def.getType() != AttributeType.REF || def.getRefEntityCode() == null) {
                continue;
            }
            Object value = record.getAttributes().get(def.getCode());
            if (value == null) {
                continue;
            }
            String targetUuid = String.valueOf(value);
            dataRepository.findByKey(def.getRefEntityCode(), targetUuid)
                    .filter(target -> !target.isDeleted())
                    .ifPresent(target -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("uuid", targetUuid);
                        item.put("display_value", target.getAttributes().get(def.getDisplayField()));
                        refDisplay.put(def.getCode(), item);
                    });
        }
        return refDisplay;
    }

    /** 构造审计条目 */
    private AuditEntry auditEntry(String operation, String targetCode, String targetUuid,
                                  String operator, String ip, String traceId,
                                  String before, String after) {
        return new AuditEntry(UUID.randomUUID().toString(), operation, TARGET_TYPE_ENTITY,
                targetCode, targetUuid, operator, System.currentTimeMillis(), before, after, ip, traceId);
    }

    /** 属性值 → JSON 快照（审计用） */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("[data] 审计快照序列化失败", e);
            return null;
        }
    }
}
