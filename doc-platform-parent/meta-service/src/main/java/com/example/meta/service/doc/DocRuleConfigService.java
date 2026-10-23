package com.example.meta.service.doc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.constants.CacheDomains;
import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.web.dto.DocRuleConfigRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据规则绑定服务（RE-05：绑定保存 + 规则引用校验 + 保存即时失效）。
 *
 * <p>绑定结构：create_rules（创建时机）+ status_rules（流转时机，status 为目标状态）。
 * 引用的规则 uuid 必须存在于 rule_lib，否则 40004。</p>
 */
@Service
public class DocRuleConfigService {

    private static final Logger log = LoggerFactory.getLogger(DocRuleConfigService.class);

    private final DocRuleConfig.Repository docRuleConfigRepository;
    private final DocSchemaDefinition.Repository docSchemaRepository;
    private final RuleLibEntry.Repository ruleLibRepository;
    private final MenuConfigRepository menuConfigRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final ObjectMapper objectMapper;
    private final ConfigAuditService configAuditService;

    public DocRuleConfigService(DocRuleConfig.Repository docRuleConfigRepository,
                                DocSchemaDefinition.Repository docSchemaRepository,
                                RuleLibEntry.Repository ruleLibRepository,
                                MenuConfigRepository menuConfigRepository,
                                CacheService cacheService,
                                SchemaInvalidationPublisher invalidationPublisher,
                                ObjectMapper objectMapper,
                                ConfigAuditService configAuditService) {
        this.docRuleConfigRepository = docRuleConfigRepository;
        this.docSchemaRepository = docSchemaRepository;
        this.ruleLibRepository = ruleLibRepository;
        this.menuConfigRepository = menuConfigRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.objectMapper = objectMapper;
        this.configAuditService = configAuditService;
    }

    /**
     * 保存规则绑定（upsert + 引用校验 + 保存即时失效）。
     *
     * @param docCode 单据编码（定义必须存在）
     * @param request 绑定请求
     * @return 保存结果视图（doc_code/version）
     */
    public Map<String, Object> save(String docCode, DocRuleConfigRequest request) {
        docSchemaRepository.findByCode(docCode).orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "单据定义不存在: " + docCode));

        List<String> createRules = request.createRules() == null
                ? List.of() : request.createRules();
        List<Map<String, Object>> statusRules = request.statusRules() == null
                ? List.of() : request.statusRules();
        validateReferences(createRules, statusRules);

        long now = System.currentTimeMillis();
        DocRuleConfig config = docRuleConfigRepository.findByDocCode(docCode).orElse(null);
        boolean isNew = config == null;
        // RD-12 before snapshot（变更前捕获，供 JSON Diff）
        Map<String, Object> beforeView = config == null ? null : snapshotView(config);
        if (isNew) {
            config = new DocRuleConfig();
            config.setDocCode(docCode);
            config.setVersion(0L);
        }
        config.setCreateRules(new ArrayList<>(createRules));
        config.setStatusRules(new ArrayList<>(statusRules));
        config.setUpdateTime(now);
        try {
            if (isNew) {
                docRuleConfigRepository.insert(config);
            } else {
                docRuleConfigRepository.updateWithVersion(config);
            }
        } catch (TsConditionCheckException e) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "规则绑定已被并发修改，请刷新后重试", e);
        }
        config.setVersion(config.getVersion() + 1);

        // 保存即时失效：DEL Redis → 广播 CONFIG_CHANGED
        cacheService.invalidateShared(CacheKeyBuilder.docRuleConfig(docCode));
        invalidationPublisher.publish(SchemaInvalidationEvent.configChanged(
                CacheDomains.DOC_RULE_CONFIG, docCode, config.getVersion()));
        bumpGlobalVersion();
        configAuditService.recordConfig("doc_rule_config", docCode, "save",
                beforeView, snapshotView(config));
        log.info("[doc-rule-config] 保存成功: docCode={}, version={}", docCode, config.getVersion());
        return Map.of("doc_code", docCode, "version", config.getVersion());
    }

    /** 查询规则绑定（未配置 40401） */
    public DocRuleConfig get(String docCode) {
        return docRuleConfigRepository.findByDocCode(docCode)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "规则绑定未配置: " + docCode));
    }

    /**
     * 运行侧契约读取（缓存回源；未配置 40401，runtime 侧映射为空绑定）。
     *
     * @param docCode 单据编码
     * @return 绑定 DTO
     */
    public DocRuleConfigDTO getPublishedDto(String docCode) {
        String key = CacheKeyBuilder.docRuleConfig(docCode);
        String cached = cacheService.get(key);
        if (cached != null) {
            return resolveCached(docCode, cached);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(CacheDomains.DOC_RULE_CONFIG, docCode);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                CacheService.CacheStamp stamp = cacheService.beginRebuild(key, true);
                DocRuleConfigDTO dto = load(docCode);
                if (dto == null) {
                    cacheService.finishRebuildNull(key, stamp, true);
                    throw notConfigured(docCode);
                }
                cacheService.finishRebuild(key, toJson(dto), stamp, true);
                return dto;
            }
            for (int i = 0; i < cacheService.rebuildMaxWaits(); i++) {
                sleep(cacheService.rebuildWaitMillis());
                cached = cacheService.get(key);
                if (cached != null) {
                    return resolveCached(docCode, cached);
                }
            }
            DocRuleConfigDTO dto = load(docCode);
            if (dto == null) {
                throw notConfigured(docCode);
            }
            return dto;
        } finally {
            if (locked) {
                cacheService.releaseRebuildLock(lockKey, lockToken);
            }
        }
    }

    // ==================== 内部工具 ====================

    /** 引用完整性校验：全部规则 uuid 必须存在于 rule_lib（40004） */
    private void validateReferences(List<String> createRules, List<Map<String, Object>> statusRules) {
        Set<String> missing = new HashSet<>();
        for (String uuid : createRules) {
            checkExists(uuid, missing);
        }
        for (Map<String, Object> statusRule : statusRules) {
            if (statusRule.get("status") == null) {
                throw new BizException(ErrorCode.REQUIRED_MISSING,
                        "status_rules 存在缺失 status 的条目");
            }
            Object rules = statusRule.get("rules");
            if (!(rules instanceof List)) {
                throw new BizException(ErrorCode.REQUIRED_MISSING,
                        "status_rules 状态「" + statusRule.get("status") + "」缺失 rules");
            }
            for (Object item : (List<?>) rules) {
                checkExists(item == null ? null : String.valueOf(item), missing);
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.REF_INVALID,
                    "绑定规则不存在: " + String.join(",", missing),
                    Map.of("missing_rules", new ArrayList<>(missing)));
        }
    }

    private void checkExists(String uuid, Set<String> missing) {
        if (uuid == null || uuid.isBlank()) {
            throw new BizException(ErrorCode.REQUIRED_MISSING, "绑定规则 uuid 不可为空");
        }
        if (ruleLibRepository.findByUuid(uuid).isEmpty()) {
            missing.add(uuid);
        }
    }

    /** 审计快照（RD-12 before/after，design/03 §2.10 JSON Diff） */
    private static Map<String, Object> snapshotView(DocRuleConfig config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("doc_code", config.getDocCode());
        view.put("version", config.getVersion());
        view.put("create_rules", config.getCreateRules());
        view.put("status_rules", config.getStatusRules());
        return view;
    }

    private static BizException notConfigured(String docCode) {
        return new BizException(ErrorCode.NOT_FOUND, "规则绑定未配置: " + docCode);
    }

    private DocRuleConfigDTO load(String docCode) {
        return docRuleConfigRepository.findByDocCode(docCode)
                .map(DocDtoMapper::toDto)
                .orElse(null);
    }

    private DocRuleConfigDTO resolveCached(String docCode, String cached) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            throw notConfigured(docCode);
        }
        try {
            return objectMapper.readValue(cached, DocRuleConfigDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("[doc-rule-config] 缓存反序列化失败，直读回源: docCode={}", docCode);
            DocRuleConfigDTO dto = load(docCode);
            if (dto == null) {
                throw notConfigured(docCode);
            }
            return dto;
        }
    }

    private void bumpGlobalVersion() {
        try {
            menuConfigRepository.incrementSchemaVersion();
        } catch (RuntimeException e) {
            log.warn("[doc-rule-config] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
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
