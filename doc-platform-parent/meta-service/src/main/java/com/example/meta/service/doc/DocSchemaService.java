package com.example.meta.service.doc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.constants.CacheDomains;
import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.infrastructure.index.DocDataIndexRegistrar;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.web.dto.DocSchemaCreateRequest;
import com.example.meta.web.dto.DocSchemaUpdateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据定义服务（DC-01~05：CRUD + 发布/停用 + 发布失效链，design/02 §四）。
 *
 * <p>定义状态口径：100 草稿 / 1 生效 / 0 停用；单据实例 current_status
 * 字典（status_defs）以 prd/12 §四为准，发布校验必含 100（暂存，初始态）
 * 与 99（关闭，终态）。</p>
 *
 * <p>审计（RD-12 / design/03 §2.10）：create/update/publish/disable/delete
 * 全部落 operation=config 审计，before/after 快照供 JSON Diff。</p>
 */
@Service
public class DocSchemaService {

    private static final Logger log = LoggerFactory.getLogger(DocSchemaService.class);

    /** 合法 data_type 值域（design/02 §四，单据域含 table 明细型） */
    private static final Set<String> VALID_DATA_TYPES = Set.of(
            "string", "integer", "decimal", "boolean", "date", "datetime", "ref", "table");

    /** status_defs 必含状态：100 暂存（初始）/ 99 关闭（终态），prd/12 §四 */
    private static final long REQUIRED_INITIAL_STATUS = 100L;
    private static final long REQUIRED_TERMINAL_STATUS = 99L;

    /** 平台保留列名：属性 code 不得与之冲突 */
    private static final Set<String> RESERVED_COLUMNS = Set.of(
            "uuid", "doc_code", "doc_name", "current_status", "creator",
            "create_dept", "create_time", "update_time", "version");

    private final DocSchemaDefinition.Repository docSchemaRepository;
    private final MenuConfigRepository menuConfigRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final DocDataIndexRegistrar docDataIndexRegistrar;
    private final ObjectMapper objectMapper;
    private final ConfigAuditService configAuditService;

    public DocSchemaService(DocSchemaDefinition.Repository docSchemaRepository,
                            MenuConfigRepository menuConfigRepository,
                            CacheService cacheService,
                            SchemaInvalidationPublisher invalidationPublisher,
                            DocDataIndexRegistrar docDataIndexRegistrar,
                            ObjectMapper objectMapper,
                            ConfigAuditService configAuditService) {
        this.docSchemaRepository = docSchemaRepository;
        this.menuConfigRepository = menuConfigRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.docDataIndexRegistrar = docDataIndexRegistrar;
        this.objectMapper = objectMapper;
        this.configAuditService = configAuditService;
    }

    // ==================== DC-01/02 创建与修改 ====================

    /**
     * 创建单据定义（DRAFT 态，version=0）。
     *
     * @param request 创建请求
     * @return 创建后的定义
     */
    public DocSchemaDefinition create(DocSchemaCreateRequest request) {
        docSchemaRepository.findByCode(request.docCode()).ifPresent(existing -> {
            throw new BizException(ErrorCode.CODE_DUPLICATE,
                    "编码已存在: " + request.docCode());
        });
        // DC-06 草稿校验：名称重复 → 40006（编码重复已在上方拦截）
        requireNameUnique(request.docName(), null);
        long now = System.currentTimeMillis();
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setUuid(UUID.randomUUID().toString());
        definition.setCode(request.docCode());
        definition.setName(request.docName());
        definition.setDocType(request.docType());
        definition.setDescription(request.description());
        definition.setStatus(DocSchemaDefinition.STATUS_DRAFT);
        definition.setVersion(0L);
        definition.setAttributes(normalizeAttributes(request.attributes()));
        definition.setStatusDefs(defaultStatusDefs(request.statusDefs()));
        definition.setCreator("system");
        definition.setCreateTime(now);
        definition.setUpdateTime(now);
        try {
            docSchemaRepository.insert(definition);
        } catch (TsConditionCheckException e) {
            // 并发撞键：findByCode 与 insert 间竞态，EXPECT_NOT_EXIST 兜底 40006
            throw new BizException(ErrorCode.CODE_DUPLICATE,
                    "编码已存在: " + request.docCode(), e);
        }
        bumpGlobalVersion();
        configAuditService.recordConfig("doc_schema", definition.getCode(),
                "create", null, snapshotOf(definition));
        log.info("[doc-schema] 创建草稿: code={}", definition.getCode());
        return definition;
    }

    /**
     * 修改单据定义（仅 DRAFT 态可修改，已发布/停用返回 40007）。
     *
     * @param code    单据编码
     * @param request 更新请求
     * @return 更新后的定义
     */
    public DocSchemaDefinition update(String code, DocSchemaUpdateRequest request) {
        DocSchemaDefinition definition = requireByCode(code);
        if (definition.getStatus() != DocSchemaDefinition.STATUS_DRAFT) {
            throw new BizException(ErrorCode.NOT_EDITABLE,
                    "「" + code + "」不可修改：仅草稿态定义可编辑");
        }
        // DC-06 草稿校验：名称重复 → 40006（排除自身）
        requireNameUnique(request.docName(), code);
        // 审计 before 快照：变更前状态（RD-12 前后值）
        Map<String, Object> before = snapshotOf(definition);
        definition.setName(request.docName());
        definition.setDocType(request.docType());
        definition.setDescription(request.description());
        definition.setAttributes(normalizeAttributes(request.attributes()));
        definition.setStatusDefs(defaultStatusDefs(request.statusDefs()));
        definition.setUpdateTime(System.currentTimeMillis());
        // version CAS：并发修改 → TsConditionCheckException → 40901（全局异常映射）
        docSchemaRepository.updateWithVersion(definition);
        definition.setVersion(definition.getVersion() + 1);
        bumpGlobalVersion();
        configAuditService.recordConfig("doc_schema", code, "update", before, snapshotOf(definition));
        log.info("[doc-schema] 更新草稿: code={}, version={}", code, definition.getVersion());
        return definition;
    }

    // ==================== DC-03 发布与停用 ====================

    /**
     * 发布单据定义（完整性校验 → 状态落库 → 索引注册 → 失效链）。
     *
     * @param code 单据编码
     * @return 发布后的定义（version+1、publishedAt 落值）
     */
    public DocSchemaDefinition publish(String code) {
        DocSchemaDefinition definition = requireByCode(code);
        if (definition.getStatus() != DocSchemaDefinition.STATUS_DRAFT) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "当前状态「" + definition.getStatus() + "」不允许发布（仅草稿可发布）");
        }
        List<String> reasons = validateForPublish(definition);
        if (!reasons.isEmpty()) {
            throw new BizException(ErrorCode.PUBLISH_INVALID, "发布校验未通过",
                    Map.of("reasons", reasons));
        }
        Map<String, Object> before = snapshotOf(definition);
        definition.setStatus(DocSchemaDefinition.STATUS_PUBLISHED);
        definition.setPublishedAt(System.currentTimeMillis());
        definition.setUpdateTime(definition.getPublishedAt());
        docSchemaRepository.updateWithVersion(definition);
        definition.setVersion(definition.getVersion() + 1);

        // doc_data_index 多元索引动态子字段注册（幂等，失败 WARN 不阻断，design/03 §4.3）
        docDataIndexRegistrar.registerQuietly(definition.getAttributes());

        // 发布失效链（≤5s）：DEL Redis → 广播 → 全局版本自增
        invalidateCache(code);
        invalidationPublisher.publish(SchemaInvalidationEvent.published(
                CacheDomains.DOC_SCHEMA, code, definition.getVersion()));
        bumpGlobalVersion();
        configAuditService.recordConfig("doc_schema", code, "publish",
                before, snapshotOf(definition));
        log.info("[doc-schema] 发布成功: code={}, version={}", code, definition.getVersion());
        return definition;
    }

    /**
     * 停用单据定义（仅已发布可停用）。
     *
     * @param code 单据编码
     * @return 停用后的定义
     */
    public DocSchemaDefinition disable(String code) {
        DocSchemaDefinition definition = requireByCode(code);
        if (definition.getStatus() != DocSchemaDefinition.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "当前状态「" + definition.getStatus() + "」不允许停用（仅已发布可停用）");
        }
        Map<String, Object> before = snapshotOf(definition);
        definition.setStatus(DocSchemaDefinition.STATUS_DISABLED);
        definition.setUpdateTime(System.currentTimeMillis());
        docSchemaRepository.updateWithVersion(definition);
        definition.setVersion(definition.getVersion() + 1);

        invalidateCache(code);
        invalidationPublisher.publish(SchemaInvalidationEvent.disabled(
                CacheDomains.DOC_SCHEMA, code, definition.getVersion()));
        bumpGlobalVersion();
        configAuditService.recordConfig("doc_schema", code, "disable",
                before, snapshotOf(definition));
        log.info("[doc-schema] 停用成功: code={}, version={}", code, definition.getVersion());
        return definition;
    }

    /**
     * 删除单据定义（仅草稿态，40009）。
     *
     * @param code 单据编码
     */
    public void delete(String code) {
        DocSchemaDefinition definition = requireByCode(code);
        if (definition.getStatus() != DocSchemaDefinition.STATUS_DRAFT) {
            throw new BizException(ErrorCode.ONLY_DRAFT_DELETE,
                    "仅草稿状态的定义可删除");
        }
        Map<String, Object> before = snapshotOf(definition);
        try {
            // 条件删除：EXPECT_EXIST + version 快照 CAS，消除 TOCTOU 窗口
            docSchemaRepository.deleteByCodeIfDraft(code, definition.getVersion());
        } catch (TsConditionCheckException e) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "「" + code + "」已被并发修改或删除，请刷新后重试", e);
        }
        bumpGlobalVersion();
        configAuditService.recordConfig("doc_schema", code, "delete", before, null);
        log.info("[doc-schema] 删除草稿: code={}", code);
    }

    // ==================== 查询 ====================

    /** 详情（含全部状态；未命中 40401） */
    public DocSchemaDefinition detail(String code) {
        return requireByCode(code);
    }

    /**
     * 列表（支持 status 筛选与关键字模糊匹配，update_time 倒序）。
     *
     * @param status  状态筛选（100/1/0，null 不过滤）
     * @param keyword 名称/编码关键字（null 不过滤）
     * @return 定义列表
     */
    public List<DocSchemaDefinition> list(Integer status, String keyword) {
        return docSchemaRepository.findAll().stream()
                .filter(def -> status == null || def.getStatus() == status)
                .filter(def -> keyword == null || keyword.isBlank()
                        || (def.getName() != null && def.getName().contains(keyword))
                        || def.getCode().contains(keyword))
                .sorted(Comparator.comparing(
                        (DocSchemaDefinition def) -> def.getUpdateTime() == null
                                ? 0L : def.getUpdateTime()).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 运行侧契约读取（仅已发布，缓存回源：L2 → L1 → 重建锁 → 回源，design/06 §5.1）。
     *
     * @param code 单据编码
     * @return 契约 DTO（未发布/不存在 40401）
     */
    public DocSchemaDTO getPublishedDto(String code) {
        String key = CacheKeyBuilder.docSchema(code);
        String cached = cacheService.get(key);
        if (cached != null) {
            return resolveCached(code, cached);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(CacheDomains.DOC_SCHEMA, code);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                long rebuildStart = System.nanoTime();
                DocSchemaDTO dto = loadPublished(code);
                if (dto == null) {
                    cacheService.putNull(key);
                    throw notFound(code);
                }
                // 回填前比对失效时间戳，防旧值覆盖新值
                cacheService.putIfNotEvicted(key, toJson(dto), rebuildStart);
                return dto;
            }
            // 未获锁：等待轮询重读，命中即返回；超限直读回源不回填
            for (int i = 0; i < cacheService.rebuildMaxWaits(); i++) {
                sleep(cacheService.rebuildWaitMillis());
                cached = cacheService.get(key);
                if (cached != null) {
                    return resolveCached(code, cached);
                }
            }
            DocSchemaDTO dto = loadPublished(code);
            if (dto == null) {
                throw notFound(code);
            }
            return dto;
        } finally {
            if (locked) {
                cacheService.releaseRebuildLock(lockKey, lockToken);
            }
        }
    }

    /** 全量已发布定义（internal 预热/对账） */
    public List<DocSchemaDTO> listPublishedDtos() {
        return docSchemaRepository.findAll().stream()
                .filter(DocSchemaDefinition::isPublished)
                .map(DocDtoMapper::toDto)
                .collect(Collectors.toList());
    }

    /** 启动预热：全量已发布定义写入两级缓存 */
    public void warmup() {
        List<DocSchemaDTO> published = listPublishedDtos();
        for (DocSchemaDTO dto : published) {
            cacheService.put(CacheKeyBuilder.docSchema(dto.getCode()), toJson(dto));
        }
        log.info("[doc-schema] 预热完成: count={}", published.size());
    }

    // ==================== 内部工具 ====================

    /** 审计快照（snake_case，含关键定义字段；RD-12 JSON Diff 素材） */
    private static Map<String, Object> snapshotOf(DocSchemaDefinition definition) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("doc_code", definition.getCode());
        snapshot.put("doc_name", definition.getName());
        snapshot.put("doc_type", definition.getDocType());
        snapshot.put("description", definition.getDescription());
        snapshot.put("status", (int) definition.getStatus());
        snapshot.put("version", definition.getVersion());
        snapshot.put("attributes", definition.getAttributes());
        snapshot.put("status_defs", definition.getStatusDefs());
        snapshot.put("published_at", definition.getPublishedAt());
        return snapshot;
    }

    /** DC-06 草稿校验：单据名称重复检查（排除自身编码；重复 → 40006） */
    private void requireNameUnique(String name, String excludeCode) {
        if (name == null || name.isBlank()) {
            return;
        }
        boolean duplicate = docSchemaRepository.findAll().stream()
                .anyMatch(def -> name.equals(def.getName())
                        && (excludeCode == null || !excludeCode.equals(def.getCode())));
        if (duplicate) {
            throw new BizException(ErrorCode.CODE_DUPLICATE, "单据名称已存在: " + name);
        }
    }

    private DocSchemaDefinition requireByCode(String code) {
        return docSchemaRepository.findByCode(code)
                .orElseThrow(() -> notFound(code));
    }

    private static BizException notFound(String code) {
        return new BizException(ErrorCode.NOT_FOUND, "单据定义不存在或未发布: " + code);
    }

    private DocSchemaDTO loadPublished(String code) {
        return docSchemaRepository.findByCode(code)
                .filter(DocSchemaDefinition::isPublished)
                .map(DocDtoMapper::toDto)
                .orElse(null);
    }

    private DocSchemaDTO resolveCached(String code, String cached) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            throw notFound(code);
        }
        try {
            return objectMapper.readValue(cached, DocSchemaDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("[doc-schema] 缓存反序列化失败，直读回源: code={}", code);
            DocSchemaDTO dto = loadPublished(code);
            if (dto == null) {
                throw notFound(code);
            }
            return dto;
        }
    }

    private void invalidateCache(String code) {
        cacheService.evict(CacheKeyBuilder.docSchema(code));
    }

    private void bumpGlobalVersion() {
        try {
            menuConfigRepository.incrementSchemaVersion();
        } catch (RuntimeException e) {
            log.warn("[doc-schema] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
        }
    }

    /** 发布完整性校验（汇总 reasons，一次性返回 40011） */
    private List<String> validateForPublish(DocSchemaDefinition definition) {
        List<String> reasons = new ArrayList<>();
        if (definition.getName() == null || definition.getName().isBlank()) {
            reasons.add("单据名称不可为空");
        }
        List<Map<String, Object>> attributes = definition.getAttributes();
        if (attributes == null || attributes.isEmpty()) {
            reasons.add("至少配置一个属性");
        } else {
            validateAttributes(attributes, reasons);
        }
        validateStatusDefs(definition.getStatusDefs(), reasons);
        return reasons;
    }

    private void validateAttributes(List<Map<String, Object>> attributes, List<String> reasons) {
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> attr : attributes) {
            String code = asString(attr.get("code"));
            if (code == null || code.isBlank()) {
                reasons.add("存在属性编码为空的属性");
                continue;
            }
            if (asString(attr.get("name")) == null) {
                reasons.add("属性「" + code + "」名称不可为空");
            }
            if (!codes.add(code)) {
                reasons.add("属性编码重复: " + code);
            }
            if (RESERVED_COLUMNS.contains(code)) {
                reasons.add("属性编码与平台保留列冲突: " + code);
            }
            String dataType = asString(attr.get("data_type"));
            dataType = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
            if (!VALID_DATA_TYPES.contains(dataType)) {
                reasons.add("属性「" + code + "」数据类型非法: " + attr.get("data_type"));
                continue;
            }
            if ("table".equals(dataType)) {
                // table 明细型必须携带 sub_attributes（design/02 §四）
                Object sub = attr.get("sub_attributes");
                if (!(sub instanceof List) || ((List<?>) sub).isEmpty()) {
                    reasons.add("属性「" + code + "」为明细表类型但未配置 sub_attributes");
                }
            }
            if ("ref".equals(dataType)
                    && (asString(attr.get("ref_entity_code")) == null
                    || asString(attr.get("ref_entity_code")).isBlank())) {
                reasons.add("属性「" + code + "」为引用类型但未指定 ref_entity_code");
            }
        }
    }

    private void validateStatusDefs(List<Map<String, Object>> statusDefs, List<String> reasons) {
        if (statusDefs == null || statusDefs.isEmpty()) {
            reasons.add("status_defs 不可为空（至少含 100 暂存与 99 关闭）");
            return;
        }
        Set<Long> statuses = new HashSet<>();
        for (Map<String, Object> def : statusDefs) {
            Long status = asLong(def.get("status"));
            if (status == null) {
                reasons.add("status_defs 存在缺失 status 的条目");
                continue;
            }
            if (!statuses.add(status)) {
                reasons.add("status_defs 状态重复: " + status);
            }
            if (asString(def.get("name")) == null) {
                reasons.add("status_defs 状态「" + status + "」缺失名称");
            }
        }
        if (!statuses.contains(REQUIRED_INITIAL_STATUS)) {
            reasons.add("status_defs 必须包含 100（暂存，初始状态）");
        }
        if (!statuses.contains(REQUIRED_TERMINAL_STATUS)) {
            reasons.add("status_defs 必须包含 99（关闭，终态）");
        }
    }

    /** 未提供 status_defs 时落 prd/12 §四五状态默认字典 */
    private List<Map<String, Object>> defaultStatusDefs(List<Map<String, Object>> provided) {
        if (provided != null && !provided.isEmpty()) {
            return provided;
        }
        return new ArrayList<>(List.of(
                Map.of("status", 100L, "name", "暂存", "editable", true, "terminal", false),
                Map.of("status", 10L, "name", "已提交", "editable", false, "terminal", false),
                Map.of("status", 20L, "name", "已审核", "editable", false, "terminal", false),
                Map.of("status", 30L, "name", "已生效", "editable", false, "terminal", false),
                Map.of("status", 99L, "name", "已关闭", "editable", false, "terminal", true)));
    }

    /**
     * 属性展示字段归一化（design/02 §二属性全字段表口径，对齐实体 SchemaViewMapper）：
     * 缺失布尔项补默认 required=false / visible=true / editable=true /
     * is_list_column=false / is_searchable=false / unique=false；
     * table 明细 sub_attributes 同层递归补齐（明细不可再嵌 table）。
     * 显式传入值（含 false）原样保留，仅补缺省。
     *
     * @param attributes 请求属性（可为 null）
     * @return 归一化后的属性列表
     */
    private static List<Map<String, Object>> normalizeAttributes(List<Map<String, Object>> attributes) {
        if (attributes == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> normalized = new ArrayList<>(attributes.size());
        for (Map<String, Object> attr : attributes) {
            normalized.add(normalizeAttribute(attr));
        }
        return normalized;
    }

    /** 单属性展示字段补默认（table 的 sub_attributes 递归；明细层禁止再嵌 table） */
    private static Map<String, Object> normalizeAttribute(Map<String, Object> attr) {
        Map<String, Object> result = new LinkedHashMap<>(attr == null ? Map.of() : attr);
        result.putIfAbsent("required", false);
        result.putIfAbsent("visible", true);
        result.putIfAbsent("editable", true);
        result.putIfAbsent("is_list_column", false);
        result.putIfAbsent("is_searchable", false);
        result.putIfAbsent("unique", false);
        Object subAttributes = result.get("sub_attributes");
        if (subAttributes instanceof List) {
            List<Object> subs = new ArrayList<>();
            for (Object sub : (List<?>) subAttributes) {
                if (sub instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> subAttr = normalizeAttribute((Map<String, Object>) sub);
                    subAttr.remove("sub_attributes");
                    subs.add(subAttr);
                }
            }
            result.put("sub_attributes", subs);
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存值序列化失败", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
