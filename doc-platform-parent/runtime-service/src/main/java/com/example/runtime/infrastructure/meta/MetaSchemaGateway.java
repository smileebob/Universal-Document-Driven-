package com.example.runtime.infrastructure.meta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.constants.CacheDomains;
import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.model.ApiResponse;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.AttributeDTO;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.api.dto.TxConfigDTO;
import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.AttributeType;
import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.DocRuleBindings;
import com.example.runtime.domain.model.DocStateMachine;
import com.example.runtime.domain.model.EntitySchema;
import com.example.runtime.domain.model.RuleDefinition;
import com.example.runtime.domain.port.DocConfigGateway;
import com.example.runtime.domain.port.SchemaGateway;
import com.example.runtime.domain.saga.TxPlan;
import feign.FeignException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 配置回源防腐网关（SchemaGateway + DocConfigGateway 唯一实现，A-04 防腐单类收口）。
 *
 * <p>阶段二评审补项：全部读取走两级缓存（L2 Caffeine → L1 Redis → 重建锁 →
 * Feign 回源），失效经 schema-invalidation 广播消费端（BC-01~06）+ TTL 兜底。</p>
 *
 * <p>meta-service-api 契约 DTO 只在本类内被触碰，domain/service 一律消费
 * 运行域模型（EntitySchema / DocDefinition / DocStateMachine / DocRuleBindings /
 * RuleDefinition / TxPlan）。</p>
 */
@Component
public class MetaSchemaGateway implements SchemaGateway, DocConfigGateway {

    private static final Logger log = LoggerFactory.getLogger(MetaSchemaGateway.class);

    private final MetaSchemaClient metaSchemaClient;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public MetaSchemaGateway(MetaSchemaClient metaSchemaClient,
                             CacheService cacheService,
                             ObjectMapper objectMapper) {
        this.metaSchemaClient = metaSchemaClient;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    // ==================== 实体定义（EC） ====================

    @Override
    public EntitySchema getEntitySchema(String entityCode) {
        EntitySchemaDTO dto = cachedDto(CacheKeyBuilder.entitySchema(entityCode),
                CacheDomains.ENTITY_SCHEMA, entityCode, EntitySchemaDTO.class,
                () -> metaSchemaClient.getEntitySchema(entityCode));
        if (dto == null) {
            throw notFound("实体定义不存在或未发布: " + entityCode);
        }
        if (dto.getStatus() != SchemaStatus.PUBLISHED) {
            throw notFound("实体定义未处于发布态，禁止数据写入: " + entityCode);
        }
        return new EntitySchema(dto.getCode(), dto.getName(), EntitySchema.STATUS_ACTIVE,
                adaptAttributes(dto.getAttributes()));
    }

    // ==================== 单据定义（DC） ====================

    @Override
    public DocDefinition getDocSchema(String docCode) {
        DocSchemaDTO dto = cachedDto(CacheKeyBuilder.docSchema(docCode),
                CacheDomains.DOC_SCHEMA, docCode, DocSchemaDTO.class,
                () -> metaSchemaClient.getDocSchema(docCode));
        if (dto == null) {
            throw notFound("单据定义不存在或未发布: " + docCode);
        }
        // 定义状态口径（design/02 §四）：1=生效；meta 侧仅返回已发布，此处防御性校验
        if (dto.getStatus() == null || dto.getStatus() != 1) {
            throw notFound("单据定义未处于生效态: " + docCode);
        }
        return adaptDocDefinition(dto);
    }

    @Override
    public DocStateMachine getStateMachine(String docCode) {
        StateMachineDTO dto = cachedDto(CacheKeyBuilder.stateMachine(docCode),
                CacheDomains.STATE_MACHINE, docCode, StateMachineDTO.class,
                () -> metaSchemaClient.getStateMachine(docCode));
        if (dto == null) {
            // 未配置状态机：返回空流转表（任何流转均非法 40003），不阻断读取
            log.warn("[schema] 状态机未配置: docCode={}", docCode);
            return new DocStateMachine(docCode, Map.of());
        }
        return adaptStateMachine(dto);
    }

    @Override
    public DocRuleBindings getDocRuleBindings(String docCode) {
        DocRuleConfigDTO dto = cachedDto(CacheKeyBuilder.docRuleConfig(docCode),
                CacheDomains.DOC_RULE_CONFIG, docCode, DocRuleConfigDTO.class,
                () -> metaSchemaClient.getDocRuleConfig(docCode));
        if (dto == null) {
            return new DocRuleBindings(docCode, List.of(), Map.of());
        }
        Map<Long, List<String>> statusRules = new LinkedHashMap<>();
        if (dto.getStatusRules() != null) {
            for (Map<String, Object> entry : dto.getStatusRules()) {
                Long status = asLong(entry.get("status"));
                if (status == null) {
                    continue;
                }
                List<String> rules = new ArrayList<>();
                Object raw = entry.get("rules");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null) {
                            rules.add(String.valueOf(item));
                        }
                    }
                }
                statusRules.put(status, rules);
            }
        }
        return new DocRuleBindings(docCode, dto.getCreateRules(), statusRules);
    }

    @Override
    public RuleDefinition getRule(String uuid) {
        RuleLibDTO dto = cachedDto(CacheKeyBuilder.rule(uuid),
                CacheDomains.RULE_LIB, uuid, RuleLibDTO.class,
                () -> metaSchemaClient.getRuleLib(uuid));
        if (dto == null) {
            throw notFound("规则不存在: " + uuid);
        }
        return new RuleDefinition(dto.getUuid(), dto.getRuleName(), dto.getRuleType(),
                dto.getExpression(), dto.getInterfaceCode(),
                Boolean.TRUE.equals(dto.getEnabled()));
    }

    // ==================== 事务配置（TX，阶段三） ====================

    @Override
    public List<TxPlan> getTxPlans(String docCode) {
        TxConfigDTO dto = cachedDto(CacheKeyBuilder.txConfig(docCode),
                CacheDomains.TX_CONFIG, docCode, TxConfigDTO.class,
                () -> metaSchemaClient.getTxConfig(docCode));
        if (dto == null || dto.getTransitions() == null) {
            // 未配置事务步骤：返回空列表（该流转无 Saga，saga_triggered=false）
            return List.of();
        }
        List<TxPlan> plans = new ArrayList<>();
        for (TxConfigDTO.TxTransitionDTO transition : dto.getTransitions()) {
            if (transition == null || transition.getFromStatus() == null
                    || transition.getToStatus() == null) {
                continue;
            }
            List<TxPlan.TxStep> steps = new ArrayList<>();
            if (transition.getSteps() != null) {
                for (TxConfigDTO.TxStepDTO step : transition.getSteps()) {
                    if (step == null || step.getSeq() == null
                            || step.getActionUrl() == null || step.getActionUrl().isBlank()) {
                        continue;
                    }
                    steps.add(new TxPlan.TxStep(step.getSeq(),
                            step.getName() == null ? "step-" + step.getSeq() : step.getName(),
                            step.getActionUrl(),
                            step.getCompensateUrl(),
                            step.getTimeoutMs() == null ? 10_000L : step.getTimeoutMs(),
                            step.getRetry() == null ? 0 : step.getRetry()));
                }
            }
            if (!steps.isEmpty()) {
                steps.sort(Comparator.comparingInt(TxPlan.TxStep::seq));
                plans.add(new TxPlan(docCode, transition.getFromStatus(),
                        transition.getToStatus(), steps));
            }
        }
        return plans;
    }

    // ==================== 两级缓存回源骨架（design/06 §5.1） ====================

    /**
     * 缓存回源通用流程：L2/L1 命中 → 重建锁获锁者回源回填（putIfNotEvicted）→
     * 未获锁 50ms×3 轮询重读，仍 miss 直读回源不回填；远端 40401 语义返回 null
     * 并写空值占位防穿透；其他错误码一律 SYSTEM_ERROR（防宕机误判为不存在）。
     */
    private <T> T cachedDto(String cacheKey, String domain, String code, Class<T> dtoType,
                            Supplier<ApiResponse<T>> fetch) {
        String cached = cacheService.get(cacheKey);
        if (cached != null) {
            return resolveCached(cacheKey, cached, dtoType, fetch);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(domain, code);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                long rebuildStart = System.nanoTime();
                T dto = fetchRemote(cacheKey, fetch);
                if (dto == null) {
                    cacheService.putNull(cacheKey);
                    return null;
                }
                cacheService.putIfNotEvicted(cacheKey, toJson(dto), rebuildStart);
                return dto;
            }
            // 未获锁：等待轮询重读，命中即返回；超限直读回源不回填
            for (int i = 0; i < cacheService.rebuildMaxWaits(); i++) {
                sleep(cacheService.rebuildWaitMillis());
                cached = cacheService.get(cacheKey);
                if (cached != null) {
                    return resolveCached(cacheKey, cached, dtoType, fetch);
                }
            }
            return fetchRemote(cacheKey, fetch);
        } finally {
            if (locked) {
                cacheService.releaseRebuildLock(lockKey, lockToken);
            }
        }
    }

    /** 缓存值解析：空值占位 → null；反序列化失败降级直读回源 */
    private <T> T resolveCached(String cacheKey, String cached, Class<T> dtoType,
                                Supplier<ApiResponse<T>> fetch) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, dtoType);
        } catch (JsonProcessingException e) {
            log.warn("[schema] 缓存反序列化失败，直读回源: key={}", cacheKey);
            return fetchRemote(cacheKey, fetch);
        }
    }

    /** Feign 回源与错误码映射（40401→null；其他→SYSTEM_ERROR） */
    private <T> T fetchRemote(String cacheKey, Supplier<ApiResponse<T>> fetch) {
        ApiResponse<T> response;
        try {
            response = fetch.get();
        } catch (FeignException e) {
            // meta 对未配置的配置项（状态机/规则绑定/规则库等）返回 HTTP 404 + body{code:40401}；
            // tx-config 返回 HTTP 200 + code 40401（形态等价）；
            // 防腐层按“未配置=未命中”语义映射 null，其余 HTTP 错误视为依赖服务不可用
            if (e.status() == 404 && extractBodyCode(e.responseBody().orElse(null)) == ErrorCode.NOT_FOUND.getCode()) {
                return null;
            }
            log.error("[schema] 配置回源失败: key={}, http={}", cacheKey, e.status(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "配置回源失败，请稍后重试");
        } catch (RuntimeException e) {
            log.error("[schema] 配置回源失败: key={}", cacheKey, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "配置回源失败，请稍后重试");
        }
        if (response == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "依赖服务暂不可用，请稍后重试");
        }
        if (response.getCode() != ApiResponse.CODE_SUCCESS) {
            // 评审修复 Major-7 沿用：仅远端明确业务 404（40401）才映射未命中；
            // Feign fallback 的 50000 与其他错误码一律视为依赖服务不可用
            if (response.getCode() == ErrorCode.NOT_FOUND.getCode()) {
                return null;
            }
            log.error("[schema] 配置回源返回错误码: key={}, code={}, message={}",
                    cacheKey, response.getCode(), response.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, "依赖服务暂不可用，请稍后重试");
        }
        return response.getData();
    }

    /** 解析 Feign 错误响应 body 中的业务 code；无 body/解析失败返回 -1 */
    private int extractBodyCode(ByteBuffer content) {
        if (content == null || !content.hasRemaining()) {
            return -1;
        }
        try {
            byte[] bytes = new byte[content.remaining()];
            content.duplicate().get(bytes);
            JsonNode node = objectMapper.readTree(bytes);
            JsonNode codeNode = node.get("code");
            if (codeNode != null && codeNode.isNumber()) {
                return codeNode.asInt();
            }
        } catch (IOException ex) {
            log.warn("[schema] 回源错误响应 body 解析 code 失败");
        }
        return -1;
    }

    // ==================== DTO → 运行域适配 ====================

    /** DocSchemaDTO → DocDefinition（attributes/status_defs 松散 Map 适配） */
    private DocDefinition adaptDocDefinition(DocSchemaDTO dto) {
        List<DocDefinition.DocAttribute> attrs = new ArrayList<>();
        if (dto.getAttributes() != null) {
            for (Map<String, Object> raw : dto.getAttributes()) {
                DocDefinition.DocAttribute attr = adaptDocAttribute(raw);
                if (attr != null) {
                    attrs.add(attr);
                }
            }
            // 保持配置侧声明顺序（单据属性无 sortOrder 契约字段）
        }
        Map<Long, DocDefinition.DocStateDef> statusDefs = new LinkedHashMap<>();
        if (dto.getStatusDefs() != null) {
            for (Map<String, Object> raw : dto.getStatusDefs()) {
                Long status = asLong(raw.get("status"));
                if (status == null) {
                    continue;
                }
                statusDefs.put(status, new DocDefinition.DocStateDef(status,
                        String.valueOf(raw.getOrDefault("name", status)),
                        asBool(raw.get("editable")), asBool(raw.get("terminal"))));
            }
        }
        return new DocDefinition(dto.getCode(), dto.getName(),
                dto.getSchemaVersion() == null ? 0L : dto.getSchemaVersion(),
                attrs, statusDefs);
    }

    /** 松散属性 Map → DocAttribute（table 型递归展开 sub_attributes） */
    private DocDefinition.DocAttribute adaptDocAttribute(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Object code = raw.get("code");
        if (code == null || String.valueOf(code).isBlank()) {
            return null;
        }
        List<DocDefinition.DocAttribute> subs = new ArrayList<>();
        Object subRaw = raw.get("sub_attributes");
        if (subRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> subMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) subMap;
                    DocDefinition.DocAttribute sub = adaptDocAttribute(typed);
                    if (sub != null) {
                        subs.add(sub);
                    }
                }
            }
        }
        return new DocDefinition.DocAttribute(
                String.valueOf(code),
                raw.get("name") == null ? String.valueOf(code) : String.valueOf(raw.get("name")),
                raw.get("data_type") == null ? "string" : String.valueOf(raw.get("data_type")),
                asBool(raw.get("required")), asBool(raw.get("unique")),
                raw.get("ref_entity_code") == null ? null : String.valueOf(raw.get("ref_entity_code")),
                raw.get("display_field") == null ? null : String.valueOf(raw.get("display_field")),
                subs);
    }

    /** StateMachineDTO → DocStateMachine（同 start 合并 end） */
    private DocStateMachine adaptStateMachine(StateMachineDTO dto) {
        Map<Long, Set<Long>> transitions = new HashMap<>();
        if (dto.getTransitions() != null) {
            for (Map<String, Object> raw : dto.getTransitions()) {
                Long start = asLong(raw.get("start"));
                if (start == null) {
                    continue;
                }
                Set<Long> ends = transitions.computeIfAbsent(start, k -> new HashSet<>());
                Object endRaw = raw.get("end");
                if (endRaw instanceof List<?> list) {
                    for (Object item : list) {
                        Long end = asLong(item);
                        if (end != null) {
                            ends.add(end);
                        }
                    }
                } else {
                    Long end = asLong(endRaw);
                    if (end != null) {
                        ends.add(end);
                    }
                }
            }
        }
        return new DocStateMachine(dto.getDocCode(), transitions);
    }

    /** AttributeDTO 列表 → 运行域 AttributeDef（按 sortOrder 升序稳定排序） */
    private List<AttributeDef> adaptAttributes(List<AttributeDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        List<AttributeDef> defs = new ArrayList<>(dtos.size());
        for (AttributeDTO dto : dtos) {
            if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
                continue;
            }
            AttributeType type = adaptType(dto);
            defs.add(new AttributeDef(dto.getCode(), dto.getName(), type,
                    dto.isRequired(), dto.isUnique(), dto.getRefEntityCode(),
                    dto.getMaxLength() == null ? 0 : dto.getMaxLength(),
                    parseDefaultValue(type, dto.getDefaultValue()),
                    // editable 属配置侧展示字段，不经契约外溢（C-02），取运行域缺省值；
                    // displayField 为 ref 展示字段（design/02），透传配置侧定义
                    true, dto.getDisplayField(),
                    dto.getSortOrder() == null ? 0 : dto.getSortOrder()));
        }
        defs.sort(Comparator.comparingInt(AttributeDef::getSortOrder));
        return defs;
    }

    /** 契约属性类型枚举 → 运行域枚举 */
    private AttributeType adaptType(AttributeDTO dto) {
        if (dto.getType() == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "实体属性类型缺失: " + dto.getCode());
        }
        return switch (dto.getType()) {
            case STRING -> AttributeType.STRING;
            case NUMBER -> AttributeType.NUMBER;
            case BOOLEAN -> AttributeType.BOOLEAN;
            case DATE -> AttributeType.DATE;
            case REF -> AttributeType.REF;
        };
    }

    /** 字符串默认值按类型解析（契约口径：运行侧按 type 解析） */
    private Object parseDefaultValue(AttributeType type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (type) {
            case NUMBER -> parseNumber(raw);
            case BOOLEAN -> Boolean.parseBoolean(raw);
            default -> raw;
        };
    }

    private Object parseNumber(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException e2) {
                log.warn("[schema] 默认值非合法数值，按原文透传: {}", raw);
                return raw;
            }
        }
    }

    // ==================== 小工具 ====================

    private static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存值序列化失败", e);
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean asBool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int asInt(Object value) {
        Long longValue = asLong(value);
        return longValue == null ? 0 : longValue.intValue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
