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
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.domain.doc.StateMachineConfig;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.web.dto.StateMachineRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 状态机配置服务（SM-01/02：保存即生效 + 合法性预校验 + 失效链，design/02 §四）。
 *
 * <p>预校验硬错误（40011 汇总 reasons）：状态缺失 status/name、流转起止状态
 * 不在状态集、99 不可作为起始、终态无出边、初始状态唯一（固定 100）；
 * 不可达状态仅告警（warnings，不阻断保存）。</p>
 */
@Service
public class StateMachineService {

    private static final Logger log = LoggerFactory.getLogger(StateMachineService.class);

    /** 单据初始状态（100 暂存，prd/12 §四） */
    private static final long INITIAL_STATUS = 100L;

    /** 关闭终态（99，不可作为任何流转的起始状态） */
    private static final long CLOSED_STATUS = 99L;

    private final StateMachineConfig.Repository stateMachineRepository;
    private final DocSchemaDefinition.Repository docSchemaRepository;
    private final MenuConfigRepository menuConfigRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final ObjectMapper objectMapper;
    private final ConfigAuditService configAuditService;

    public StateMachineService(StateMachineConfig.Repository stateMachineRepository,
                               DocSchemaDefinition.Repository docSchemaRepository,
                               MenuConfigRepository menuConfigRepository,
                               CacheService cacheService,
                               SchemaInvalidationPublisher invalidationPublisher,
                               ObjectMapper objectMapper,
                               ConfigAuditService configAuditService) {
        this.stateMachineRepository = stateMachineRepository;
        this.docSchemaRepository = docSchemaRepository;
        this.menuConfigRepository = menuConfigRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.objectMapper = objectMapper;
        this.configAuditService = configAuditService;
    }

    /**
     * 保存状态机（upsert + 预校验 + 保存即时失效）。
     *
     * @param docCode 单据编码（定义必须存在）
     * @param request 状态与流转配置
     * @return 保存结果视图（doc_code/version/warnings）
     */
    public Map<String, Object> save(String docCode, StateMachineRequest request) {
        docSchemaRepository.findByCode(docCode).orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "单据定义不存在: " + docCode));

        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validate(docCode, request.states(),
                request.transitions() == null ? List.of() : request.transitions(),
                reasons, warnings);
        if (!reasons.isEmpty()) {
            throw new BizException(ErrorCode.PUBLISH_INVALID, "状态机校验未通过",
                    Map.of("reasons", reasons));
        }

        long now = System.currentTimeMillis();
        StateMachineConfig config = stateMachineRepository.findByDocCode(docCode).orElse(null);
        boolean isNew = config == null;
        // RD-12 before snapshot（变更前捕获，供 JSON Diff）
        Map<String, Object> beforeView = config == null ? null : snapshotView(config);
        if (isNew) {
            config = new StateMachineConfig();
            config.setDocCode(docCode);
            config.setVersion(0L);
        }
        config.setStates(request.states());
        config.setTransitions(request.transitions() == null ? List.of() : request.transitions());
        config.setUpdateTime(now);
        try {
            if (isNew) {
                stateMachineRepository.insert(config);
            } else {
                stateMachineRepository.updateWithVersion(config);
            }
        } catch (TsConditionCheckException e) {
            // 并发保存竞态：条件失败映射 40901，提示重试
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "状态机已被并发修改，请刷新后重试", e);
        }
        config.setVersion(config.getVersion() + 1);

        // 保存即时失效（复用发布失效链）：DEL Redis → 广播 CONFIG_CHANGED
        cacheService.invalidateShared(CacheKeyBuilder.stateMachine(docCode));
        invalidationPublisher.publish(SchemaInvalidationEvent.configChanged(
                CacheDomains.STATE_MACHINE, docCode, config.getVersion()));
        bumpGlobalVersion();
        if (!warnings.isEmpty()) {
            log.warn("[state-machine] 保存含告警: docCode={}, warnings={}", docCode, warnings);
        }
        configAuditService.recordConfig("state_machine", docCode, "save",
                beforeView, snapshotView(config));
        log.info("[state-machine] 保存成功: docCode={}, version={}", docCode, config.getVersion());
        return Map.of("doc_code", docCode, "version", config.getVersion(), "warnings", warnings);
    }

    /** 查询状态机配置（未配置 40401） */
    public StateMachineConfig get(String docCode) {
        return stateMachineRepository.findByDocCode(docCode)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "状态机未配置: " + docCode));
    }

    /**
     * 运行侧契约读取（缓存回源；未配置 40401，runtime 侧映射为空流转表）。
     *
     * @param docCode 单据编码
     * @return 状态机 DTO
     */
    public StateMachineDTO getPublishedDto(String docCode) {
        String key = CacheKeyBuilder.stateMachine(docCode);
        String cached = cacheService.get(key);
        if (cached != null) {
            return resolveCached(docCode, cached);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(CacheDomains.STATE_MACHINE, docCode);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                CacheService.CacheStamp stamp = cacheService.beginRebuild(key, true);
                StateMachineDTO dto = load(docCode);
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
            StateMachineDTO dto = load(docCode);
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

    // ==================== 预校验（design/02 §四） ====================

    private void validate(String docCode, List<Map<String, Object>> states,
                          List<Map<String, Object>> transitions,
                          List<String> reasons, List<String> warnings) {
        Set<Long> stateSet = new HashSet<>();
        Set<Long> terminalStates = new HashSet<>();
        for (Map<String, Object> state : states) {
            Long status = asLong(state.get("status"));
            if (status == null) {
                reasons.add("states 存在缺失 status 的条目");
                continue;
            }
            if (!stateSet.add(status)) {
                reasons.add("states 状态重复: " + status);
            }
            if (state.get("name") == null) {
                reasons.add("states 状态「" + status + "」缺失名称");
            }
            if (Boolean.TRUE.equals(state.get("terminal"))) {
                terminalStates.add(status);
            }
        }
        if (!stateSet.contains(INITIAL_STATUS)) {
            reasons.add("起始状态唯一且固定为 100（暂存），states 必须包含");
        }

        Set<Long> reachable = new HashSet<>();
        reachable.add(INITIAL_STATUS);
        for (Map<String, Object> transition : transitions) {
            Long start = asLong(transition.get("start"));
            if (start == null) {
                reasons.add("transitions 存在缺失 start 的条目");
                continue;
            }
            if (!stateSet.contains(start)) {
                reasons.add("流转起始状态未定义: " + start);
            }
            if (start != null && start == CLOSED_STATUS) {
                reasons.add("99（关闭）不可作为流转起始状态");
            }
            if (start != null && terminalStates.contains(start)) {
                reasons.add("终态无出边：状态 " + start + " 已标记 terminal");
            }
            Object end = transition.get("end");
            if (!(end instanceof List) || ((List<?>) end).isEmpty()) {
                reasons.add("流转「" + start + "」缺失目标状态 end");
                continue;
            }
            for (Object item : (List<?>) end) {
                Long target = asLong(item);
                if (target == null) {
                    reasons.add("流转「" + start + "」end 含非法状态值");
                    continue;
                }
                if (!stateSet.contains(target)) {
                    reasons.add("流转目标状态未定义: " + target);
                } else {
                    reachable.add(target);
                }
            }
        }
        // 不可达状态告警（不阻断保存）：非初始态且不作为任何流转目标
        for (Long status : stateSet) {
            if (!reachable.contains(status)) {
                warnings.add("状态 " + status + " 不可达（无流转路径可达）");
            }
        }
        if (!warnings.isEmpty()) {
            log.warn("[state-machine] 预校验告警: docCode={}, warnings={}", docCode, warnings);
        }
    }

    // ==================== 内部工具 ====================

    private static BizException notConfigured(String docCode) {
        return new BizException(ErrorCode.NOT_FOUND, "状态机未配置: " + docCode);
    }

    private StateMachineDTO load(String docCode) {
        return stateMachineRepository.findByDocCode(docCode)
                .map(DocDtoMapper::toDto)
                .orElse(null);
    }

    private StateMachineDTO resolveCached(String docCode, String cached) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            throw notConfigured(docCode);
        }
        try {
            return objectMapper.readValue(cached, StateMachineDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("[state-machine] 缓存反序列化失败，直读回源: docCode={}", docCode);
            StateMachineDTO dto = load(docCode);
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
            log.warn("[state-machine] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
        }
    }

    /** 审计快照（RD-12 before/after，design/03 §2.10 JSON Diff） */
    private static Map<String, Object> snapshotView(StateMachineConfig config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("doc_code", config.getDocCode());
        view.put("version", config.getVersion());
        view.put("states", config.getStates());
        view.put("transitions", config.getTransitions());
        return view;
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
