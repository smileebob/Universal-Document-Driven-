package com.example.meta.service.doc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.web.dto.DocRuleConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocRuleConfigService RE-05 业务测试（全 Mockito：绑定保存 + 引用校验 + 即时失效）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocRuleConfigServiceTest {

    @Mock
    private DocRuleConfig.Repository docRuleConfigRepository;
    @Mock
    private DocSchemaDefinition.Repository docSchemaRepository;
    @Mock
    private RuleLibEntry.Repository ruleLibRepository;
    @Mock
    private MenuConfigRepository menuConfigRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private SchemaInvalidationPublisher publisher;
    @Mock
    private ConfigAuditService configAuditService;

    private DocRuleConfigService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new DocRuleConfigService(docRuleConfigRepository, docSchemaRepository,
                ruleLibRepository, menuConfigRepository, cacheService, publisher,
                objectMapper, configAuditService);
        when(menuConfigRepository.incrementSchemaVersion()).thenReturn(1L);
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(new DocSchemaDefinition()));
        when(ruleLibRepository.findByUuid("r1"))
                .thenReturn(Optional.of(new RuleLibEntry()));
        when(ruleLibRepository.findByUuid("r2"))
                .thenReturn(Optional.of(new RuleLibEntry()));
    }

    @Test
    @DisplayName("保存：新建 insert + 即时失效链 + 审计，返回 doc_code/version")
    void save_new_ok() {
        when(docRuleConfigRepository.findByDocCode("po")).thenReturn(Optional.empty());

        Map<String, Object> result = service.save("po", new DocRuleConfigRequest(
                List.of("r1"),
                List.of(Map.of("status", 10L, "rules", List.of("r2")))));

        assertThat(result.get("doc_code")).isEqualTo("po");
        assertThat(result.get("version")).isEqualTo(1L);
        verify(docRuleConfigRepository).insert(any(DocRuleConfig.class));
        verify(cacheService).evict(CacheKeyBuilder.docRuleConfig("po"));
        verify(publisher).publish(any(SchemaInvalidationEvent.class));
        verify(configAuditService).recordConfig(eq("doc_rule_config"), eq("po"), eq("save"), any(), any());
    }

    @Test
    @DisplayName("保存：已存在走 updateWithVersion；null 入参落空集合")
    void save_update_ok_nullSafe() {
        DocRuleConfig existing = new DocRuleConfig();
        existing.setDocCode("po");
        existing.setVersion(2L);
        when(docRuleConfigRepository.findByDocCode("po")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.save("po", new DocRuleConfigRequest(null, null));
        assertThat(result.get("version")).isEqualTo(3L);
        assertThat(existing.getCreateRules()).isEmpty();
        assertThat(existing.getStatusRules()).isEmpty();
        verify(docRuleConfigRepository).updateWithVersion(existing);
    }

    @Test
    @DisplayName("保存：单据定义不存在抛 40401")
    void save_docNotFound_40401() {
        when(docSchemaRepository.findByCode("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save("ghost", new DocRuleConfigRequest(null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("保存：引用规则不存在抛 40004 且 missing_rules 汇总")
    void save_missingRules_40004() {
        when(ruleLibRepository.findByUuid("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save("po", new DocRuleConfigRequest(
                List.of("r1", "gone"),
                List.of(Map.of("status", 10L, "rules", List.of("gone"))))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40004);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    assertThat((List<String>) data.get("missing_rules")).containsExactly("gone");
                });
        verify(docRuleConfigRepository, never()).insert(any());
    }

    @Test
    @DisplayName("保存：status_rules 结构校验（缺 status/rules 40001，空 uuid 40001）")
    void save_statusRules_structure_40001() {
        assertThatThrownBy(() -> service.save("po", new DocRuleConfigRequest(
                List.of(), List.of(Map.of("rules", List.of("r1"))))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);

        assertThatThrownBy(() -> service.save("po", new DocRuleConfigRequest(
                List.of(), List.of(Map.of("status", 10L)))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);

        assertThatThrownBy(() -> service.save("po", new DocRuleConfigRequest(
                List.of(" "), List.of())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("保存：并发 CAS 冲突抛 40901")
    void save_conflict_40901() {
        when(docRuleConfigRepository.findByDocCode("po")).thenReturn(Optional.empty());
        doThrow(new TsConditionCheckException("condition check fail"))
                .when(docRuleConfigRepository).insert(any());
        assertThatThrownBy(() -> service.save("po", new DocRuleConfigRequest(null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    @Test
    @DisplayName("get：命中返回；未配置 40401")
    void get() {
        DocRuleConfig config = new DocRuleConfig();
        config.setDocCode("po");
        when(docRuleConfigRepository.findByDocCode("po")).thenReturn(Optional.of(config));
        assertThat(service.get("po").getDocCode()).isEqualTo("po");
        when(docRuleConfigRepository.findByDocCode("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("getPublishedDto：缓存命中/空值占位/回源回填/未配置 40401")
    void getPublishedDto_paths() throws Exception {
        DocRuleConfig config = new DocRuleConfig();
        config.setDocCode("po");
        config.setVersion(1L);
        config.setCreateRules(List.of("r1"));
        DocRuleConfigDTO dto = DocDtoMapper.toDto(config);

        when(cacheService.get(CacheKeyBuilder.docRuleConfig("po")))
                .thenReturn(objectMapper.writeValueAsString(dto));
        assertThat(service.getPublishedDto("po").getDocCode()).isEqualTo("po");

        when(cacheService.get(CacheKeyBuilder.docRuleConfig("null")))
                .thenReturn(CacheService.NULL_PLACEHOLDER);
        assertThatThrownBy(() -> service.getPublishedDto("null"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);

        when(cacheService.get(CacheKeyBuilder.docRuleConfig("po2"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("token");
        when(docRuleConfigRepository.findByDocCode("po2")).thenReturn(Optional.of(config));
        assertThat(service.getPublishedDto("po2").getDocCode()).isEqualTo("po");
        verify(cacheService).putIfNotEvicted(anyString(), anyString(), anyLong());

        when(cacheService.get(CacheKeyBuilder.docRuleConfig("ghost"))).thenReturn(null);
        when(docRuleConfigRepository.findByDocCode("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublishedDto("ghost"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
        verify(cacheService).putNull(CacheKeyBuilder.docRuleConfig("ghost"));
    }
}
