package com.example.meta.service.txconfig;

import java.util.ArrayList;
import java.util.HashSet;
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
import com.example.meta.api.dto.TxConfigDTO;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.domain.txconfig.TxConfig;
import com.example.meta.service.audit.ConfigAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单据事务步骤配置服务（TX-01：tx_config CRUD + 保存即时失效，design/08 §3.3）。
 *
 * <p>保存链路与 DocRuleConfigService 同构：校验 → upsert → DEL 缓存键 →
 * 广播 CONFIG_CHANGED（tx_config 域）→ 全局 schema_version 自增 → 配置审计。
 * 失效 version 水位取 update_time（毫秒单调，BC-05 乱序过滤可用）。</p>
 */
@Service
public class TxConfigService {

    /** 步骤超时上限（Saga 总预算 60s 约束下的单步上限） */
    private static final long MAX_STEP_TIMEOUT_MS = 60_000L;

    /** 连接类异常重试次数上限（平台封顶，design/05 F7 派生） */
    private static final int MAX_STEP_RETRY = 3;

    private static final TypeReference<List<TxConfigDTO.TxTransitionDTO>> TRANSITIONS_TYPE =
            new TypeReference<>() {
            };

    private static final Logger log = LoggerFactory.getLogger(TxConfigService.class);

    private final TxConfig.Repository txConfigRepository;
    private final DocSchemaDefinition.Repository docSchemaRepository;
    private final MenuConfigRepository menuConfigRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final ConfigAuditService configAuditService;
    private final ObjectMapper objectMapper;

    public TxConfigService(TxConfig.Repository txConfigRepository,
                           DocSchemaDefinition.Repository docSchemaRepository,
                           MenuConfigRepository menuConfigRepository,
                           CacheService cacheService,
                           SchemaInvalidationPublisher invalidationPublisher,
                           ConfigAuditService configAuditService,
                           ObjectMapper objectMapper) {
        this.txConfigRepository = txConfigRepository;
        this.docSchemaRepository = docSchemaRepository;
        this.menuConfigRepository = menuConfigRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.configAuditService = configAuditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存事务步骤配置（upsert + 保存即时失效）。
     *
     * @param docCode 单据编码（定义必须存在）
     * @param dto     配置请求（transitions 可为空列表=清空编排，流转不触发 Saga）
     * @return 保存结果视图（doc_code/update_time）
     */
    public Map<String, Object> save(String docCode, TxConfigDTO dto) {
        docSchemaRepository.findByCode(docCode).orElseThrow(() ->
                new BizException(ErrorCode.NOT_FOUND, "单据定义不存在: " + docCode));
        List<TxConfigDTO.TxTransitionDTO> transitions =
                validateAndNormalize(dto == null ? null : dto.getTransitions());
        // RD-12 before snapshot（变更前读取旧配置，供 JSON Diff）
        TxConfig beforeConfig = txConfigRepository.findByDocCode(docCode).orElse(null);

        long now = System.currentTimeMillis();
        TxConfig config = new TxConfig();
        config.setDocCode(docCode);
        config.setTxStepsJson(toJson(transitions));
        config.setUpdateTime(now);
        txConfigRepository.save(config);

        // 保存即时失效：DEL 缓存键 → 广播 CONFIG_CHANGED（tx_config 域）
        cacheService.invalidateShared(CacheKeyBuilder.txConfig(docCode));
        invalidationPublisher.publish(SchemaInvalidationEvent.configChanged(
                CacheDomains.TX_CONFIG, docCode, now));
        bumpGlobalVersion();
        configAuditService.recordConfig("tx_config", docCode, "save",
                beforeConfig == null ? null : Map.of("transitions", beforeConfig.getTxStepsJson()),
                Map.of("transitions", transitions));
        log.info("[tx-config] 保存成功: docCode={}, transitions={}, updateTime={}",
                docCode, transitions.size(), now);
        return Map.of("doc_code", docCode, "update_time", now);
    }

    /**
     * 查询事务配置 DTO（GET 接口与 internal 契约共用）。
     *
     * @param docCode 单据编码
     * @return 配置 DTO（未配置返回 null，由调用方决定 40401 呈现形态）
     */
    public TxConfigDTO getDto(String docCode) {
        return txConfigRepository.findByDocCode(docCode).map(config -> {
            TxConfigDTO dto = new TxConfigDTO();
            dto.setDocCode(docCode);
            dto.setTransitions(parseTransitions(config.getTxStepsJson()));
            dto.setUpdateTime(config.getUpdateTime());
            return dto;
        }).orElse(null);
    }

    // ==================== 校验与归一化（prd/04 TX-01） ====================

    /**
     * 结构校验 + 缺省值归一：timeout_ms 默认 10000（≤60000）、retry 默认 0（≤3）、
     * seq 必填且流转内唯一、action_url 必填 http(s)、同一
     * (from_status,to_status) 不可重复。非法值一律 40002（TYPE_INVALID）。
     */
    private List<TxConfigDTO.TxTransitionDTO> validateAndNormalize(
            List<TxConfigDTO.TxTransitionDTO> transitions) {
        if (transitions == null) {
            return new ArrayList<>();
        }
        Set<String> transitionKeys = new HashSet<>();
        for (TxConfigDTO.TxTransitionDTO transition : transitions) {
            if (transition.getFromStatus() == null || transition.getToStatus() == null) {
                throw new BizException(ErrorCode.REQUIRED_MISSING,
                        "transitions 条目缺失 from_status/to_status");
            }
            String key = transition.getFromStatus() + "->" + transition.getToStatus();
            if (!transitionKeys.add(key)) {
                throw new BizException(ErrorCode.TYPE_INVALID, "重复的流转事务配置: " + key);
            }
            List<TxConfigDTO.TxStepDTO> steps = transition.getSteps();
            if (steps == null || steps.isEmpty()) {
                throw new BizException(ErrorCode.REQUIRED_MISSING,
                        "流转 " + key + " 缺失 steps");
            }
            Set<Integer> seqs = new HashSet<>();
            for (TxConfigDTO.TxStepDTO step : steps) {
                if (step.getSeq() == null || step.getSeq() < 1) {
                    throw new BizException(ErrorCode.REQUIRED_MISSING,
                            "流转 " + key + " 存在非法 seq（须 ≥1）");
                }
                if (!seqs.add(step.getSeq())) {
                    throw new BizException(ErrorCode.TYPE_INVALID,
                            "流转 " + key + " 步骤 seq 重复: " + step.getSeq());
                }
                if (step.getName() == null || step.getName().isBlank()) {
                    throw new BizException(ErrorCode.REQUIRED_MISSING,
                            "流转 " + key + " 步骤 " + step.getSeq() + " 缺失 name");
                }
                if (!isHttpUrl(step.getActionUrl())) {
                    throw new BizException(ErrorCode.TYPE_INVALID,
                            "流转 " + key + " 步骤 " + step.getSeq() + " action_url 非法");
                }
                if (step.getCompensateUrl() != null && !step.getCompensateUrl().isBlank()
                        && !isHttpUrl(step.getCompensateUrl())) {
                    throw new BizException(ErrorCode.TYPE_INVALID,
                            "流转 " + key + " 步骤 " + step.getSeq() + " compensate_url 非法");
                }
                long timeout = step.getTimeoutMs() == null ? 10_000L : step.getTimeoutMs();
                if (timeout < 1 || timeout > MAX_STEP_TIMEOUT_MS) {
                    throw new BizException(ErrorCode.TYPE_INVALID,
                            "流转 " + key + " 步骤 " + step.getSeq()
                                    + " timeout_ms 越界（1~" + MAX_STEP_TIMEOUT_MS + "）");
                }
                int retry = step.getRetry() == null ? 0 : step.getRetry();
                if (retry < 0 || retry > MAX_STEP_RETRY) {
                    throw new BizException(ErrorCode.TYPE_INVALID,
                            "流转 " + key + " 步骤 " + step.getSeq()
                                    + " retry 越界（0~" + MAX_STEP_RETRY + "）");
                }
                step.setTimeoutMs(timeout);
                step.setRetry(retry);
            }
            steps.sort((a, b) -> Integer.compare(a.getSeq(), b.getSeq()));
        }
        return new ArrayList<>(transitions);
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private List<TxConfigDTO.TxTransitionDTO> parseTransitions(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<TxConfigDTO.TxTransitionDTO> value = objectMapper.readValue(json, TRANSITIONS_TYPE);
            return value == null ? new ArrayList<>() : value;
        } catch (JsonProcessingException e) {
            log.error("[tx-config] tx_steps JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void bumpGlobalVersion() {
        try {
            menuConfigRepository.incrementSchemaVersion();
        } catch (RuntimeException e) {
            log.warn("[tx-config] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tx_steps JSON 序列化失败", e);
        }
    }
}
