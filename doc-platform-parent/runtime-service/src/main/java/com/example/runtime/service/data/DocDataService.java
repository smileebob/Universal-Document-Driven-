package com.example.runtime.service.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.DocDefinition.DocAttribute;
import com.example.runtime.domain.port.DocConfigGateway;
import com.example.runtime.domain.port.DocDataStore;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.rule.DocRuleChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据数据 CRUD 服务（RD-05~08，design/09 §2.1 / prd/04 DC-05）。
 *
 * <ul>
 *   <li>创建：current_status=100 暂存（prd/12 §四），create_rules 规则校验
 *       失败 42201 + failed_rules；主行 + 创建历史行（seq=1）同分区事务；</li>
 *   <li>修改：仅可编辑状态（{@link DocDefinition#isEditableStatus}）允许，
 *       否则 40008；version 乐观锁 + current_status 快照 CAS → 冲突 40901；</li>
 *   <li>删除：仅 100 暂存可删（40009），逻辑删 deleted=1；</li>
 *   <li>审计：成功后异步旁路（区别于流转同事务审计，design/03 §2.10）。</li>
 * </ul>
 */
@Service
public class DocDataService {

    private static final Logger log = LoggerFactory.getLogger(DocDataService.class);

    /** 审计目标类型（prd/12 §1.9 口径） */
    private static final String TARGET_TYPE_DOC = "doc";

    private final DocConfigGateway configGateway;
    private final DocDataStore docDataStore;
    private final DocRuleChecker ruleChecker;
    private final AuditService auditService;

    public DocDataService(DocConfigGateway configGateway, DocDataStore docDataStore,
                          DocRuleChecker ruleChecker, AuditService auditService) {
        this.configGateway = configGateway;
        this.docDataStore = docDataStore;
        this.ruleChecker = ruleChecker;
        this.auditService = auditService;
    }

    // ==================== 创建（RD-05） ====================

    /**
     * 创建单据（初始状态 100 暂存）。
     *
     * @param docCode    单据编码（定义须处于生效态）
     * @param attributes 头属性载荷（table 明细为 List&lt;Map&gt;）
     * @param operator   操作人（X-User-Id 权威值）
     * @param dept       创建部门（X-User-Dept）
     * @return {@code {uuid, doc_code, current_status, version}}
     */
    public Map<String, Object> create(String docCode, Map<String, Object> attributes,
                                      String operator, String dept) {
        DocDefinition definition = configGateway.getDocSchema(docCode);
        validateAttributes(definition, attributes);

        // uuid 预生成：create_rules 的 relations 五元组需要 source_uuid
        String uuid = UUID.randomUUID().toString();
        // create_rules 规则校验（事务外；失败短路 42201 + failed_rules）
        List<Map<String, Object>> failedRules =
                ruleChecker.checkCreate(docCode, uuid, definition, attributes, operator);
        if (!failedRules.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("failed_rules", failedRules);
            data.put("saga_executed", false);
            throw new BizException(ErrorCode.RULE_FAILED, "规则校验未通过", data);
        }

        long now = System.currentTimeMillis();
        DocDataRecord record = new DocDataRecord(uuid, docCode,
                DocDefinition.INITIAL_STATUS, 1L, attributes, buildDocRelations(definition, attributes), false,
                operator, dept, now, now);
        docDataStore.create(record, operator);

        auditService.recordAsync(new AuditEntry(UUID.randomUUID().toString(), "create",
                TARGET_TYPE_DOC, docCode, uuid, operator, now, null,
                docSnapshot(DocDefinition.INITIAL_STATUS, 1L, attributes), null, MDC.get("traceId")));
        log.info("[doc-data] 创建成功: {}/{}, operator={}", docCode, uuid, operator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        result.put("doc_code", docCode);
        result.put("current_status", DocDefinition.INITIAL_STATUS);
        result.put("version", 1L);
        return result;
    }

    // ==================== 详情（RD-07） ====================

    /**
     * 单据详情（主键点查，状态名按字典投影）。
     *
     * @return snake_case 详情视图
     */
    public Map<String, Object> detail(String docCode, String uuid) {
        DocDefinition definition = configGateway.getDocSchema(docCode);
        DocDataRecord record = requireRecord(docCode, uuid);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uuid", record.getUuid());
        view.put("doc_code", docCode);
        view.put("current_status", record.getCurrentStatus());
        view.put("status_name", definition.stateName(record.getCurrentStatus()));
        view.put("version", record.getVersion());
        view.put("attributes", record.getAttributes());
        view.put("relations", record.getRelations());
        view.put("creator", record.getCreator());
        view.put("create_dept", record.getCreateDept());
        view.put("create_time", record.getCreateTime());
        view.put("update_time", record.getUpdateTime());
        return view;
    }

    // ==================== 状态历史（RD-08 status_history 读接口） ====================

    /**
     * 单据状态变更历史（doc_data_history 伴生表，seq 升序，前端状态时间线契约）。
     *
     * <p>定义不存在 / 主行不存在或已删 → 40401；记录字段名与表定义对齐
     * （seq/from_status/to_status/operation/operator/operate_time/version_after），
     * 附状态字典名投影 from_status_name/to_status_name。</p>
     *
     * @return snake_case 记录列表（含状态名投影）
     */
    public List<Map<String, Object>> history(String docCode, String uuid) {
        DocDefinition definition = configGateway.getDocSchema(docCode);
        requireRecord(docCode, uuid);
        List<Map<String, Object>> rows = docDataStore.findHistory(docCode, uuid);
        for (Map<String, Object> row : rows) {
            Object from = row.get("from_status");
            row.put("from_status_name", from == null
                    ? null : definition.stateName(((Number) from).longValue()));
            Object to = row.get("to_status");
            row.put("to_status_name", to == null
                    ? null : definition.stateName(((Number) to).longValue()));
        }
        return rows;
    }

    // ==================== 修改（RD-06） ====================

    /**
     * 修改单据（仅可编辑状态；version 乐观锁必填）。
     *
     * @param expectVersion 期望版本（不等当前版本即 40901）
     * @return {@code {uuid, doc_code, current_status, version}}
     */
    public Map<String, Object> update(String docCode, String uuid, Map<String, Object> attributes,
                                      long expectVersion, String operator) {
        DocDefinition definition = configGateway.getDocSchema(docCode);
        DocDataRecord record = requireRecord(docCode, uuid);
        if (record.getVersion() != expectVersion) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "单据已被他人修改，请刷新后重试: version=" + record.getVersion());
        }
        // 状态编辑约束（prd/04：仅暂存/声明 editable 的状态可改）
        if (!definition.isEditableStatus(record.getCurrentStatus())) {
            throw new BizException(ErrorCode.STATE_NOT_EDITABLE,
                    "当前状态不允许修改: " + definition.stateName(record.getCurrentStatus()));
        }
        validateAttributes(definition, attributes);

        boolean updated = docDataStore.updateEditable(docCode, uuid, expectVersion,
                record.getCurrentStatus(), attributes, buildDocRelations(definition, attributes),
                System.currentTimeMillis());
        if (!updated) {
            // current_status 快照 CAS 失败：并发流转/修改改变了状态
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "单据状态已被并发修改，请刷新后重试");
        }

        auditService.recordAsync(new AuditEntry(UUID.randomUUID().toString(), "update",
                TARGET_TYPE_DOC, docCode, uuid, operator, System.currentTimeMillis(),
                docSnapshot(record.getCurrentStatus(), record.getVersion(), record.getAttributes()),
                docSnapshot(record.getCurrentStatus(), expectVersion + 1, attributes), null, MDC.get("traceId")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        result.put("doc_code", docCode);
        result.put("current_status", record.getCurrentStatus());
        result.put("version", expectVersion + 1);
        return result;
    }

    // ==================== 逻辑删（RD-08 类约束） ====================

    /**
     * 逻辑删除（仅 100 暂存可删，40009）。
     *
     * @return {@code {uuid, deleted}}
     */
    public Map<String, Object> delete(String docCode, String uuid, String operator) {
        configGateway.getDocSchema(docCode);
        DocDataRecord record = requireRecord(docCode, uuid);
        if (record.getCurrentStatus() != DocDefinition.INITIAL_STATUS) {
            throw new BizException(ErrorCode.ONLY_DRAFT_DELETE, "仅暂存状态的单据可删除");
        }
        boolean deleted = docDataStore.logicalDelete(docCode, uuid, record.getVersion(),
                System.currentTimeMillis());
        if (!deleted) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "删除条件冲突，请刷新后重试");
        }
        auditService.recordAsync(new AuditEntry(UUID.randomUUID().toString(), "delete",
                TARGET_TYPE_DOC, docCode, uuid, operator, System.currentTimeMillis(),
                docSnapshot(record.getCurrentStatus(), record.getVersion(), record.getAttributes()),
                null, null, MDC.get("traceId")));
        return Map.of("uuid", uuid, "deleted", true);
    }

    // ==================== 属性校验（头属性 + table 明细） ====================

    /**
     * 单据属性轻量校验：未知属性拒绝、必填缺失 40001、数值/布尔类型 40002、
     * table 明细行结构（List&lt;Map&gt;）与子属性必填递归校验。
     */
    private void validateAttributes(DocDefinition definition, Map<String, Object> attributes) {
        Map<String, Object> payload = attributes == null ? Map.of() : attributes;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (definition.findAttribute(entry.getKey()) == null) {
                throw new BizException(ErrorCode.TYPE_INVALID,
                        "未定义的属性: " + entry.getKey());
            }
        }
        for (DocAttribute attr : definition.getAttributes()) {
            Object value = payload.get(attr.getCode());
            validateSingle(attr, value, attr.getCode());
        }
    }

    /** 单属性校验（table 型递归明细行） */
    private void validateSingle(DocAttribute attr, Object value, String path) {
        boolean missing = value == null || (value instanceof String str && str.isBlank());
        if (missing) {
            if (attr.isRequired()) {
                throw new BizException(ErrorCode.REQUIRED_MISSING, "「" + path + "」为必填项");
            }
            return;
        }
        if (attr.isTable()) {
            validateTable(attr, value, path);
            return;
        }
        if ("number".equalsIgnoreCase(attr.getDataType()) && !(value instanceof Number)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "「" + path + "」须为数值");
        }
        if ("boolean".equalsIgnoreCase(attr.getDataType()) && !(value instanceof Boolean)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "「" + path + "」须为布尔值");
        }
    }

    /** table 明细校验：行须为 JSON 对象，子属性递归必填/类型校验 */
    private void validateTable(DocAttribute attr, Object value, String path) {
        if (!(value instanceof List<?> rows)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "「" + path + "」须为明细数组");
        }
        for (int i = 0; i < rows.size(); i++) {
            Object rowObj = rows.get(i);
            if (!(rowObj instanceof Map<?, ?> row)) {
                throw new BizException(ErrorCode.TYPE_INVALID,
                        "「" + path + "[" + i + "]」须为 JSON 对象");
            }
            for (DocAttribute sub : attr.getSubAttributes()) {
                Object subValue = row.get(sub.getCode());
                validateSingle(sub, subValue, path + "[" + i + "]." + sub.getCode());
            }
            for (Object keyObj : row.keySet()) {
                if (attr.getSubAttributes().stream()
                        .noneMatch(sub -> sub.getCode().equals(String.valueOf(keyObj)))) {
                    throw new BizException(ErrorCode.TYPE_INVALID,
                            "「" + path + "[" + i + "]」包含未定义子属性: " + keyObj);
                }
            }
        }
    }

    // ==================== RD-09 relations 投影 ====================

    /**
     * REF 属性 → relations 投影（RD-09 关联查询的嵌套索引来源）。
     *
     * <p>条目结构：{type, code, uuid, attribute_code}；
     * 头属性 ref 与 table 明细行内 ref 子属性均逐引用一条。</p>
     */
    private List<Map<String, Object>> buildDocRelations(DocDefinition definition,
                                                        Map<String, Object> attributes) {
        List<Map<String, Object>> relations = new ArrayList<>();
        if (attributes == null) {
            return relations;
        }
        for (DocAttribute attr : definition.getAttributes()) {
            if (attr.isTable()) {
                Object rowsObj = attributes.get(attr.getCode());
                if (rowsObj instanceof List<?> rows) {
                    for (Object rowObj : rows) {
                        if (rowObj instanceof Map<?, ?> row) {
                            for (DocAttribute sub : attr.getSubAttributes()) {
                                appendRefRelation(relations, sub, row.get(sub.getCode()));
                            }
                        }
                    }
                }
                continue;
            }
            appendRefRelation(relations, attr, attributes.get(attr.getCode()));
        }
        return relations;
    }

    /** 单个 ref 属性值 → relation 条目（非 ref/空值跳过） */
    private void appendRefRelation(List<Map<String, Object>> relations, DocAttribute attr,
                                   Object value) {
        if (!"ref".equalsIgnoreCase(attr.getDataType()) || attr.getRefEntityCode() == null
                || value == null || String.valueOf(value).isBlank()) {
            return;
        }
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("type", "biz_entity");
        relation.put("code", attr.getRefEntityCode());
        relation.put("uuid", String.valueOf(value));
        relation.put("attribute_code", attr.getCode());
        relations.add(relation);
    }

    // ==================== RD-12 审计快照 ====================

    /** 审计快照序列化器（ObjectMapper 线程安全，静态共享） */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    /** 审计快照 JSON（current_status/version/attributes，JSON Diff 可用，design/03 §2.10） */
    private static String docSnapshot(long currentStatus, long version, Map<String, Object> attributes) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("current_status", currentStatus);
        snapshot.put("version", version);
        snapshot.put("attributes", attributes == null ? Map.of() : attributes);
        try {
            return AUDIT_MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("[doc-data] 审计快照序列化失败，降级空快照", e);
            return null;
        }
    }

    /** 主行点查（不存在/已删 → 40401） */
    private DocDataRecord requireRecord(String docCode, String uuid) {
        return docDataStore.find(docCode, uuid)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "单据不存在或已删除: " + docCode + "/" + uuid));
    }
}

