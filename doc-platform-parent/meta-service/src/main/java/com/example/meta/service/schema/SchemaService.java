package com.example.meta.service.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.common.constants.CacheDomains;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntitySchemaRepository;
import com.example.meta.domain.schema.EntityStatus;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.domain.schema.MenuEntry;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.meta.infrastructure.index.EntityDataIndexRegistrar;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.web.dto.AttributeSpec;
import com.example.meta.web.dto.EntityCreateRequest;
import com.example.meta.web.dto.EntityUpdateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实体定义服务（EC-01~04：创建/修改/发布/停用/删除/详情/列表 + 发布失效链）。
 *
 * <p>发布失效链（design/06 §5.2，≤5s）：写表成功 → DEL Redis Key →
 * 发送 schema-invalidation 广播（自包含 code+version+action）。广播未建线时
 * Caffeine TTL≤5min 兜底（BC-03）。</p>
 *
 * <p>审计（RD-12 / design/03 §2.10）：create/update/publish/disable/delete
 * 全部落 operation=config 审计（target_type=entity_schema），before/after 快照。</p>
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    /** 平台保留列名：unique 属性 code 不得与之冲突（运行侧平台列） */
    private static final Set<String> RESERVED_COLUMNS = Set.of(
            "uuid", "creator", "create_dept", "create_time", "update_time", "status", "entity_code");

    /** 合法 data_type 值域（design/02 §二） */
    private static final Set<String> VALID_DATA_TYPES = Set.of(
            "string", "integer", "decimal", "boolean", "date", "datetime", "ref", "table");

    private final EntitySchemaRepository schemaRepository;
    private final MenuConfigRepository menuConfigRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final EntityDataIndexRegistrar indexRegistrar;
    private final ObjectMapper objectMapper;
    private final ConfigAuditService configAuditService;

    public SchemaService(EntitySchemaRepository schemaRepository,
                         MenuConfigRepository menuConfigRepository,
                         CacheService cacheService,
                         SchemaInvalidationPublisher invalidationPublisher,
                         EntityDataIndexRegistrar indexRegistrar,
                         ObjectMapper objectMapper,
                         ConfigAuditService configAuditService) {
        this.schemaRepository = schemaRepository;
        this.menuConfigRepository = menuConfigRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.indexRegistrar = indexRegistrar;
        this.objectMapper = objectMapper;
        this.configAuditService = configAuditService;
    }

    // ==================== EC-01/02 创建与修改 ====================

    /**
     * 创建实体定义（DRAFT 态，version=0）。
     *
     * @param request 创建请求
     * @return 创建后的定义
     */
    public EntitySchema create(EntityCreateRequest request) {
        schemaRepository.findByCode(request.getEntityCode()).ifPresent(existing -> {
            throw new BizException(ErrorCode.CODE_DUPLICATE,
                    "编码已存在: " + request.getEntityCode());
        });
        long now = System.currentTimeMillis();
        EntitySchema schema = new EntitySchema();
        schema.setCode(request.getEntityCode());
        schema.setName(request.getEntityName());
        schema.setEntityType(request.getEntityType());
        schema.setDescription(request.getDescription());
        schema.setStatus(EntityStatus.DRAFT);
        schema.setVersion(0L);
        schema.setAttributes(toDefs(request.getAttributes()));
        schema.setCreator("system");
        schema.setCreateDept("system");
        schema.setCreateTime(now);
        schema.setUpdateTime(now);
        try {
            schemaRepository.insert(schema);
        } catch (TsConditionCheckException e) {
            // 并发撞键（评审修复 Minor-12）：findByCode 与 insert 间竞态，
            // EXPECT_NOT_EXIST 条件失败兼容兑底为 40006
            throw new BizException(ErrorCode.CODE_DUPLICATE,
                    "编码已存在: " + request.getEntityCode(), e);
        }
        bumpGlobalVersion();
        configAuditService.recordConfig("entity_schema", schema.getCode(),
                "create", null, snapshotOf(schema));
        log.info("[entity-schema] 创建草稿: code={}", schema.getCode());
        return schema;
    }

    /**
     * 修改实体定义（仅 DRAFT 态可修改，已发布/停用返回 40007）。
     *
     * @param code    实体编码
     * @param request 更新请求
     * @return 更新后的定义
     */
    public EntitySchema update(String code, EntityUpdateRequest request) {
        EntitySchema schema = requireByCode(code);
        if (schema.getStatus() != EntityStatus.DRAFT) {
            throw new BizException(ErrorCode.NOT_EDITABLE,
                    "「" + code + "」不可修改：仅草稿态定义可编辑");
        }
        // 审计 before 快照：变更前状态（RD-12 前后值）
        Map<String, Object> before = snapshotOf(schema);
        schema.setName(request.getEntityName());
        schema.setEntityType(request.getEntityType());
        schema.setDescription(request.getDescription());
        schema.setAttributes(toDefs(request.getAttributes()));
        schema.setUpdateTime(System.currentTimeMillis());
        // version CAS：并发修改 → TsConditionCheckException → 40901
        schemaRepository.updateWithVersion(schema);
        schema.setVersion(schema.getVersion() + 1);
        bumpGlobalVersion();
        configAuditService.recordConfig("entity_schema", code, "update", before, snapshotOf(schema));
        log.info("[entity-schema] 更新草稿: code={}, version={}", code, schema.getVersion());
        return schema;
    }

    // ==================== EC-03 发布与停用 ====================

    /**
     * 发布实体定义（完整性校验 → 状态/版本落库 → 索引注册 → 失效链）。
     *
     * @param code 实体编码
     * @return 发布后的定义（version+1、publishedAt 落值）
     */
    public EntitySchema publish(String code) {
        EntitySchema schema = requireByCode(code);
        if (schema.getStatus() != EntityStatus.DRAFT) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "当前状态「" + schema.getStatus() + "」不允许发布（仅草稿可发布）");
        }
        List<String> reasons = validateForPublish(schema);
        if (!reasons.isEmpty()) {
            throw new BizException(ErrorCode.PUBLISH_INVALID, "发布校验未通过",
                    Map.of("reasons", reasons));
        }
        Map<String, Object> before = snapshotOf(schema);
        schema.setStatus(EntityStatus.PUBLISHED);
        schema.setPublishedAt(System.currentTimeMillis());
        schema.setUpdateTime(schema.getPublishedAt());
        schemaRepository.updateWithVersion(schema);
        schema.setVersion(schema.getVersion() + 1);

        // 多元索引动态子字段注册（幂等，失败 WARN 不阻断）
        indexRegistrar.registerQuietly(schema.getAttributes());

        // 发布失效链（≤5s）：DEL Redis → 广播
        invalidateCache(code);
        invalidationPublisher.publish(SchemaInvalidationEvent.published(
                CacheDomains.ENTITY_SCHEMA, code, schema.getVersion()));
        bumpGlobalVersion();
        configAuditService.recordConfig("entity_schema", code, "publish",
                before, snapshotOf(schema));
        log.info("[entity-schema] 发布成功: code={}, version={}", code, schema.getVersion());
        return schema;
    }

    /**
     * 停用实体定义（仅 PUBLISHED 可停用；级联取消菜单挂载）。
     *
     * @param code 实体编码
     * @return 停用后的定义
     */
    public EntitySchema disable(String code) {
        EntitySchema schema = requireByCode(code);
        if (schema.getStatus() != EntityStatus.PUBLISHED) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "当前状态「" + schema.getStatus() + "」不允许停用（仅已发布可停用）");
        }
        Map<String, Object> before = snapshotOf(schema);
        schema.setStatus(EntityStatus.DISABLED);
        schema.setUpdateTime(System.currentTimeMillis());
        schemaRepository.updateWithVersion(schema);
        schema.setVersion(schema.getVersion() + 1);

        // 级联：menu_config 对应挂载项置 enabled=0（design/03 §2.9）
        menuConfigRepository.findByTarget("entity", code).ifPresent(entry -> {
            if (entry.isEnabled()) {
                entry.setEnabled(false);
                entry.setUpdateTime(System.currentTimeMillis());
                menuConfigRepository.save(entry);
                cacheService.evict(CacheKeyBuilder.menuTree());
            }
        });

        invalidateCache(code);
        invalidationPublisher.publish(SchemaInvalidationEvent.disabled(
                CacheDomains.ENTITY_SCHEMA, code, schema.getVersion()));
        bumpGlobalVersion();
        configAuditService.recordConfig("entity_schema", code, "disable",
                before, snapshotOf(schema));
        log.info("[entity-schema] 停用成功: code={}, version={}", code, schema.getVersion());
        return schema;
    }

    /**
     * 删除实体定义（仅草稿态，RD-07 口径）。
     *
     * @param code 实体编码
     */
    public void delete(String code) {
        EntitySchema schema = requireByCode(code);
        if (schema.getStatus() != EntityStatus.DRAFT) {
            throw new BizException(ErrorCode.ONLY_DRAFT_DELETE,
                    "仅草稿状态的定义可删除");
        }
        Map<String, Object> before = snapshotOf(schema);
        try {
            // 条件删除（评审修复 Major-6）：EXPECT_EXIST + version 快照 CAS，
            // 消除读后判态→无条件删除的 TOCTOU 窗口
            schemaRepository.deleteByCodeIfDraft(code, schema.getVersion());
        } catch (TsConditionCheckException e) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "「" + code + "」已被并发修改或删除，请刷新后重试", e);
        }
        bumpGlobalVersion();
        configAuditService.recordConfig("entity_schema", code, "delete", before, null);
        log.info("[entity-schema] 删除草稿: code={}", code);
    }

    // ==================== 查询 ====================

    /** 详情（含全部状态；未命中 40401） */
    public EntitySchema detail(String code) {
        return requireByCode(code);
    }

    /**
     * 列表（支持 status 数值筛选与关键字模糊匹配，update_time 倒序）。
     *
     * @param status  状态筛选（100/1/0，null 不过滤）
     * @param keyword 名称/编码关键字（null 不过滤）
     * @return 定义列表
     */
    public List<EntitySchema> list(Integer status, String keyword) {
        return schemaRepository.findAll().stream()
                .filter(schema -> status == null
                        || schema.getStatus().getStorageValue() == status)
                .filter(schema -> keyword == null || keyword.isBlank()
                        || (schema.getName() != null && schema.getName().contains(keyword))
                        || schema.getCode().contains(keyword))
                .sorted(Comparator.comparing(
                        (EntitySchema schema) -> schema.getUpdateTime() == null
                                ? 0L : schema.getUpdateTime()).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 运行侧契约读取（仅已发布，缓存回源：L2 → L1 → 重建锁 → 回源，design/06 §5.1）。
     *
     * @param code 实体编码
     * @return 契约 DTO（未发布/不存在 40401）
     */
    public EntitySchemaDTO getPublishedDto(String code) {
        String key = CacheKeyBuilder.entitySchema(code);
        String cached = cacheService.get(key);
        if (cached != null) {
            return resolveCached(code, cached);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(CacheDomains.ENTITY_SCHEMA, code);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                long rebuildStart = System.nanoTime();
                EntitySchemaDTO dto = loadPublished(code);
                if (dto == null) {
                    cacheService.putNull(key);
                    throw notFound(code);
                }
                // 回填前比对失效时间戳（评审修复 Minor-10），防旧值覆盖新值
                cacheService.putIfNotEvicted(key, toJson(dto), rebuildStart);
                return dto;
            }
            // 未获锁：等待 50ms 轮询重读，命中即返回；超限直读回源不回填
            for (int i = 0; i < cacheService.rebuildMaxWaits(); i++) {
                sleep(cacheService.rebuildWaitMillis());
                cached = cacheService.get(key);
                if (cached != null) {
                    return resolveCached(code, cached);
                }
            }
            EntitySchemaDTO dto = loadPublished(code);
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
    public List<EntitySchemaDTO> listPublishedDtos() {
        return schemaRepository.findAll().stream()
                .filter(EntitySchema::isPublished)
                .map(SchemaDtoMapper::toDto)
                .collect(Collectors.toList());
    }

    /** 启动预热：全量已发布定义写入两级缓存（容错由 CacheWarmupRunner 承担） */
    public void warmup() {
        List<EntitySchemaDTO> published = listPublishedDtos();
        for (EntitySchemaDTO dto : published) {
            cacheService.put(CacheKeyBuilder.entitySchema(dto.getCode()), toJson(dto));
        }
        log.info("[entity-schema] 预热完成: count={}", published.size());
    }

    // ==================== 内部工具 ====================

    /** 审计快照（snake_case，含关键定义字段；RD-12 JSON Diff 素材） */
    private static Map<String, Object> snapshotOf(EntitySchema schema) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("entity_code", schema.getCode());
        snapshot.put("entity_name", schema.getName());
        snapshot.put("entity_type", schema.getEntityType());
        snapshot.put("description", schema.getDescription());
        snapshot.put("status", schema.getStatus() == null
                ? null : schema.getStatus().getStorageValue());
        snapshot.put("version", schema.getVersion());
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (AttributeDef def : schema.getAttributes()) {
            Map<String, Object> attr = new LinkedHashMap<>();
            attr.put("code", def.getCode());
            attr.put("name", def.getName());
            attr.put("data_type", def.getDataType());
            attr.put("required", def.isRequired());
            attr.put("unique", def.isUnique());
            attr.put("ref_entity_code", def.getRefEntityCode());
            attr.put("display_field", def.getDisplayField());
            attributes.add(attr);
        }
        snapshot.put("attributes", attributes);
        snapshot.put("published_at", schema.getPublishedAt());
        return snapshot;
    }

    private EntitySchema requireByCode(String code) {
        return schemaRepository.findByCode(code)
                .orElseThrow(() -> notFound(code));
    }

    private static BizException notFound(String code) {
        return new BizException(ErrorCode.NOT_FOUND, "实体定义不存在或未发布: " + code);
    }

    private EntitySchemaDTO loadPublished(String code) {
        return schemaRepository.findByCode(code)
                .filter(EntitySchema::isPublished)
                .map(SchemaDtoMapper::toDto)
                .orElse(null);
    }

    private EntitySchemaDTO resolveCached(String code, String cached) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            throw notFound(code);
        }
        try {
            return objectMapper.readValue(cached, EntitySchemaDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("[entity-schema] 缓存反序列化失败，直读回源: code={}", code);
            EntitySchemaDTO dto = loadPublished(code);
            if (dto == null) {
                throw notFound(code);
            }
            return dto;
        }
    }

    private void invalidateCache(String code) {
        cacheService.evict(CacheKeyBuilder.entitySchema(code));
    }

    private void bumpGlobalVersion() {
        try {
            menuConfigRepository.incrementSchemaVersion();
        } catch (RuntimeException e) {
            log.warn("[entity-schema] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
        }
    }

    /** 发布完整性校验（汇总 reasons，一次性返回 40011） */
    private List<String> validateForPublish(EntitySchema schema) {
        List<String> reasons = new ArrayList<>();
        if (schema.getName() == null || schema.getName().isBlank()) {
            reasons.add("实体名称不可为空");
        }
        List<AttributeDef> attributes = schema.getAttributes();
        if (attributes.isEmpty()) {
            reasons.add("至少配置一个属性");
        }
        Set<String> codes = new HashSet<>();
        for (AttributeDef def : attributes) {
            if (def.getCode() == null || def.getCode().isBlank()) {
                reasons.add("存在属性编码为空的属性");
                continue;
            }
            if (def.getName() == null || def.getName().isBlank()) {
                reasons.add("属性「" + def.getCode() + "」名称不可为空");
            }
            if (!codes.add(def.getCode())) {
                reasons.add("属性编码重复: " + def.getCode());
            }
            if (RESERVED_COLUMNS.contains(def.getCode())) {
                reasons.add("属性编码与平台保留列冲突: " + def.getCode());
            }
            String dataType = def.getDataType() == null
                    ? "" : def.getDataType().toLowerCase(Locale.ROOT);
            if (!VALID_DATA_TYPES.contains(dataType)) {
                reasons.add("属性「" + def.getCode() + "」数据类型非法: " + def.getDataType());
                continue;
            }
            if ("ref".equals(dataType)) {
                if (def.getRefEntityCode() == null || def.getRefEntityCode().isBlank()) {
                    reasons.add("属性「" + def.getCode() + "」为引用类型但未指定 ref_entity_code");
                } else {
                    boolean targetPublished = schemaRepository.findByCode(def.getRefEntityCode())
                            .map(EntitySchema::isPublished)
                            .orElse(false);
                    if (!targetPublished) {
                        reasons.add("属性「" + def.getCode() + "」引用的实体「"
                                + def.getRefEntityCode() + "」不存在或未发布");
                    }
                }
            }
        }
        return reasons;
    }

    private static List<AttributeDef> toDefs(List<AttributeSpec> specs) {
        List<AttributeDef> defs = new ArrayList<>();
        if (specs == null) {
            return defs;
        }
        for (AttributeSpec spec : specs) {
            defs.add(SchemaViewMapper.toDef(spec));
        }
        return defs;
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
