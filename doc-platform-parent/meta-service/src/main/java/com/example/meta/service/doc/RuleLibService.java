package com.example.meta.service.doc;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.example.common.rule.RuleEvalResult;
import com.example.common.rule.RuleExecutor;
import com.example.common.rule.RuleSpec;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.web.dto.RuleCreateRequest;
import com.example.meta.web.dto.RuleTestRequest;
import com.example.meta.web.dto.RuleUpdateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 规则库服务（RE-01/02：CRUD + 在线测试 + 删除引用保护 + 失效链）。
 *
 * <p>在线测试与运行时共用 doc-common {@link RuleExecutor}（SpEL 只读沙箱、
 * 2s 超时、编译缓存），保证测试与执行语义一致（design/08 §四）。</p>
 */
@Service
public class RuleLibService {

    private static final Logger log = LoggerFactory.getLogger(RuleLibService.class);

    private final RuleLibEntry.Repository ruleLibRepository;
    private final DocRuleConfig.Repository docRuleConfigRepository;
    private final MenuConfigRepository menuConfigRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final RuleExecutor ruleExecutor;
    private final ObjectMapper objectMapper;
    private final ConfigAuditService configAuditService;

    public RuleLibService(RuleLibEntry.Repository ruleLibRepository,
                          DocRuleConfig.Repository docRuleConfigRepository,
                          MenuConfigRepository menuConfigRepository,
                          CacheService cacheService,
                          SchemaInvalidationPublisher invalidationPublisher,
                          RuleExecutor ruleExecutor,
                          ObjectMapper objectMapper,
                          ConfigAuditService configAuditService) {
        this.ruleLibRepository = ruleLibRepository;
        this.docRuleConfigRepository = docRuleConfigRepository;
        this.menuConfigRepository = menuConfigRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.ruleExecutor = ruleExecutor;
        this.objectMapper = objectMapper;
        this.configAuditService = configAuditService;
    }

    // ==================== RE-01/02 CRUD ====================

    /**
     * 创建规则（uuid 服务端生成，version=0）。
     *
     * @param request 创建请求
     * @return 创建后的规则条目
     */
    public RuleLibEntry create(RuleCreateRequest request) {
        validatePayload(request.ruleType(), request.expression(), request.interfaceCode());
        long now = System.currentTimeMillis();
        RuleLibEntry entry = new RuleLibEntry();
        entry.setUuid(UUID.randomUUID().toString());
        entry.setRuleName(request.ruleName());
        entry.setRuleType(request.ruleType());
        entry.setExpression(request.expression());
        entry.setInterfaceCode(request.interfaceCode());
        entry.setEnabled(request.enabled() == null || request.enabled());
        entry.setDescription(request.description());
        entry.setVersion(0L);
        entry.setCreator("system");
        entry.setCreateTime(now);
        entry.setUpdateTime(now);
        try {
            ruleLibRepository.insert(entry);
        } catch (TsConditionCheckException e) {
            throw new BizException(ErrorCode.CODE_DUPLICATE,
                    "规则 uuid 冲突，请重试: " + entry.getUuid(), e);
        }
        bumpGlobalVersion();
        configAuditService.recordConfig("rule_lib", entry.getUuid(),
                "create", null, ruleSnapshot(entry));
        log.info("[rule-lib] 创建规则: uuid={}, type={}", entry.getUuid(), entry.getRuleType());
        return entry;
    }

    /**
     * 修改规则（version CAS；保存即时失效）。
     *
     * @param uuid    规则唯一标识
     * @param request 更新请求
     * @return 更新后的规则条目
     */
    public RuleLibEntry update(String uuid, RuleUpdateRequest request) {
        RuleLibEntry entry = requireByUuid(uuid);
        // RD-12 before snapshot（变更前捕获，供 JSON Diff）
        Map<String, Object> beforeRule = ruleSnapshot(entry);
        validatePayload(request.ruleType(), request.expression(), request.interfaceCode());
        entry.setRuleName(request.ruleName());
        entry.setRuleType(request.ruleType());
        entry.setExpression(request.expression());
        entry.setInterfaceCode(request.interfaceCode());
        if (request.enabled() != null) {
            entry.setEnabled(request.enabled());
        }
        entry.setDescription(request.description());
        entry.setUpdateTime(System.currentTimeMillis());
        ruleLibRepository.updateWithVersion(entry);
        entry.setVersion(entry.getVersion() + 1);
        configAuditService.recordConfig("rule_lib", uuid,
                "update", beforeRule, ruleSnapshot(entry));
        invalidateRule(uuid, entry.getVersion());
        log.info("[rule-lib] 更新规则: uuid={}, version={}", uuid, entry.getVersion());
        return entry;
    }

    /**
     * 删除规则（被 doc_rule_config 引用时 40010；条件删除 CAS）。
     *
     * @param uuid 规则唯一标识
     */
    public void delete(String uuid) {
        RuleLibEntry entry = requireByUuid(uuid);
        List<String> boundDocs = findBoundDocCodes(uuid);
        if (!boundDocs.isEmpty()) {
            throw new BizException(ErrorCode.REF_PROTECTED,
                    "规则已被单据绑定，禁止删除: " + uuid,
                    Map.of("bound_docs", boundDocs));
        }
        try {
            ruleLibRepository.deleteByUuidIfVersion(uuid, entry.getVersion());
        } catch (TsConditionCheckException e) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "规则已被并发修改或删除，请刷新后重试", e);
        }
        configAuditService.recordConfig("rule_lib", uuid, "delete", ruleSnapshot(entry), null);
        invalidateRule(uuid, entry.getVersion() + 1);
        log.info("[rule-lib] 删除规则: uuid={}", uuid);
    }

    /** 详情（未命中 40401） */
    public RuleLibEntry detail(String uuid) {
        return requireByUuid(uuid);
    }

    /**
     * 列表（关键字/启用状态过滤，update_time 倒序）。
     *
     * @param keyword 名称关键字（null 不过滤）
     * @param enabled 启用状态（null 不过滤）
     * @return 规则列表
     */
    public List<RuleLibEntry> list(String keyword, Boolean enabled) {
        return ruleLibRepository.findAll().stream()
                .filter(entry -> enabled == null || entry.isEnabled() == enabled)
                .filter(entry -> keyword == null || keyword.isBlank()
                        || (entry.getRuleName() != null && entry.getRuleName().contains(keyword)))
                .sorted(Comparator.comparing(
                        (RuleLibEntry entry) -> entry.getUpdateTime() == null
                                ? 0L : entry.getUpdateTime()).reversed())
                .collect(Collectors.toList());
    }

    // ==================== RE-02 在线测试 ====================

    /**
     * 规则在线测试（与运行时同一执行器）。
     *
     * @param uuid    规则唯一标识
     * @param request 测试上下文（attributes/relations/current_status 等）
     * @return 求值结果视图（passed/skipped/reason）
     */
    public Map<String, Object> test(String uuid, RuleTestRequest request) {
        RuleLibEntry entry = requireByUuid(uuid);
        RuleSpec spec = new RuleSpec(entry.getUuid(), entry.getRuleName(),
                entry.getRuleType(), entry.getExpression(), true);
        String contextJson = toJson(request == null || request.context() == null
                ? Map.of() : request.context());
        RuleEvalResult result = ruleExecutor.test(spec, contextJson);
        log.info("[rule-lib] 在线测试: uuid={}, passed={}, skipped={}",
                uuid, result.isPassed(), result.isSkipped());
        return Map.of(
                "rule_uuid", result.getRuleUuid(),
                "passed", result.isPassed(),
                "skipped", result.isSkipped(),
                "reason", result.getReason() == null ? "" : result.getReason());
    }

    // ==================== 运行侧契约（缓存回源） ====================

    /**
     * 运行侧契约读取（缓存回源；停用规则同样可见，执行器按 skip 语义处理）。
     *
     * @param uuid 规则唯一标识
     * @return 规则 DTO（未命中 40401）
     */
    public RuleLibDTO getPublishedDto(String uuid) {
        String key = CacheKeyBuilder.rule(uuid);
        String cached = cacheService.get(key);
        if (cached != null) {
            return resolveCached(uuid, cached);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(CacheDomains.RULE_LIB, uuid);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                long rebuildStart = System.nanoTime();
                RuleLibDTO dto = load(uuid);
                if (dto == null) {
                    cacheService.putNull(key);
                    throw notFound(uuid);
                }
                cacheService.putIfNotEvicted(key, toJson(dto), rebuildStart);
                return dto;
            }
            for (int i = 0; i < cacheService.rebuildMaxWaits(); i++) {
                sleep(cacheService.rebuildWaitMillis());
                cached = cacheService.get(key);
                if (cached != null) {
                    return resolveCached(uuid, cached);
                }
            }
            RuleLibDTO dto = load(uuid);
            if (dto == null) {
                throw notFound(uuid);
            }
            return dto;
        } finally {
            if (locked) {
                cacheService.releaseRebuildLock(lockKey, lockToken);
            }
        }
    }

    // ==================== 内部工具 ====================

    /** 审计快照（RD-12 before/after，design/03 §2.10 JSON Diff） */
    private static Map<String, Object> ruleSnapshot(RuleLibEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uuid", entry.getUuid());
        view.put("rule_name", entry.getRuleName());
        view.put("rule_type", entry.getRuleType());
        view.put("expression", entry.getExpression());
        view.put("interface_code", entry.getInterfaceCode());
        view.put("enabled", entry.isEnabled());
        view.put("version", entry.getVersion());
        return view;
    }

    private RuleLibEntry requireByUuid(String uuid) {
        return ruleLibRepository.findByUuid(uuid)
                .orElseThrow(() -> notFound(uuid));
    }

    private static BizException notFound(String uuid) {
        return new BizException(ErrorCode.NOT_FOUND, "规则不存在: " + uuid);
    }

    /** 载荷校验：rule_type 值域 + expression/interface 互补约束 + 长度上限 */
    private void validatePayload(String ruleType, String expression, String interfaceCode) {
        if (RuleLibEntry.TYPE_EXPRESSION.equalsIgnoreCase(ruleType)) {
            if (expression == null || expression.isBlank()) {
                throw new BizException(ErrorCode.REQUIRED_MISSING,
                        "expression 型规则必须提供 expression");
            }
            if (expression.length() > RuleSpec.MAX_EXPRESSION_LENGTH) {
                throw new BizException(ErrorCode.TYPE_INVALID,
                        "expression 长度超限（≤" + RuleSpec.MAX_EXPRESSION_LENGTH + "）");
            }
        } else if (RuleLibEntry.TYPE_INTERFACE.equalsIgnoreCase(ruleType)) {
            if (interfaceCode == null || interfaceCode.isBlank()) {
                throw new BizException(ErrorCode.REQUIRED_MISSING,
                        "interface 型规则必须提供 interface_code");
            }
        } else {
            throw new BizException(ErrorCode.TYPE_INVALID,
                    "rule_type 非法（仅支持 expression/interface）: " + ruleType);
        }
    }

    /** 反引用扫描：返回绑定该规则的单据编码列表 */
    private List<String> findBoundDocCodes(String uuid) {
        List<String> bound = new java.util.ArrayList<>();
        for (DocRuleConfig config : docRuleConfigRepository.findAll()) {
            boolean inCreate = config.getCreateRules() != null
                    && config.getCreateRules().contains(uuid);
            boolean inStatus = false;
            if (config.getStatusRules() != null) {
                for (Map<String, Object> statusRule : config.getStatusRules()) {
                    Object rules = statusRule.get("rules");
                    if (rules instanceof List && ((List<?>) rules).stream()
                            .anyMatch(item -> uuid.equals(String.valueOf(item)))) {
                        inStatus = true;
                        break;
                    }
                }
            }
            if (inCreate || inStatus) {
                bound.add(config.getDocCode());
            }
        }
        return bound;
    }

    private void invalidateRule(String uuid, long version) {
        cacheService.evict(CacheKeyBuilder.rule(uuid));
        invalidationPublisher.publish(SchemaInvalidationEvent.configChanged(
                CacheDomains.RULE_LIB, uuid, version));
        bumpGlobalVersion();
    }

    private RuleLibDTO load(String uuid) {
        return ruleLibRepository.findByUuid(uuid)
                .map(DocDtoMapper::toDto)
                .orElse(null);
    }

    private RuleLibDTO resolveCached(String uuid, String cached) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            throw notFound(uuid);
        }
        try {
            return objectMapper.readValue(cached, RuleLibDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("[rule-lib] 缓存反序列化失败，直读回源: uuid={}", uuid);
            RuleLibDTO dto = load(uuid);
            if (dto == null) {
                throw notFound(uuid);
            }
            return dto;
        }
    }

    private void bumpGlobalVersion() {
        try {
            menuConfigRepository.incrementSchemaVersion();
        } catch (RuntimeException e) {
            log.warn("[rule-lib] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
        }
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

