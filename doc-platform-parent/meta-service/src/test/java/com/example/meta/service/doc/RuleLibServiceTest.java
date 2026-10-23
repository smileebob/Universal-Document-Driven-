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
import com.example.common.rule.RuleEvalResult;
import com.example.common.rule.RuleExecutor;
import com.example.common.rule.RuleSpec;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.web.dto.RuleCreateRequest;
import com.example.meta.web.dto.RuleTestRequest;
import com.example.meta.web.dto.RuleUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RuleLibService RE-01/02 业务测试（全 Mockito：CRUD + 在线测试 + 删除引用保护）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleLibServiceTest {

    @Mock
    private RuleLibEntry.Repository ruleLibRepository;
    @Mock
    private DocRuleConfig.Repository docRuleConfigRepository;
    @Mock
    private MenuConfigRepository menuConfigRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private SchemaInvalidationPublisher publisher;
    @Mock
    private RuleExecutor ruleExecutor;
    @Mock
    private ConfigAuditService auditService;

    private RuleLibService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new RuleLibService(ruleLibRepository, docRuleConfigRepository,
                menuConfigRepository, cacheService, publisher, ruleExecutor, objectMapper, auditService);
        when(menuConfigRepository.incrementSchemaVersion()).thenReturn(1L);
    }

    private RuleLibEntry entry(String uuid, long version) {
        RuleLibEntry entry = new RuleLibEntry();
        entry.setUuid(uuid);
        entry.setRuleName(uuid + "-name");
        entry.setRuleType("expression");
        entry.setExpression("#amount > 0");
        entry.setEnabled(true);
        entry.setVersion(version);
        return entry;
    }

    // ==================== RE-01 CRUD ====================

    @Test
    @DisplayName("创建：expression 型 uuid 服务端生成 version=0；enabled 缺省为 true")
    void create_expression_ok() {
        RuleLibEntry created = service.create(new RuleCreateRequest(
                "金额校验", "expression", "#amount > 0", null, null, "desc"));
        assertThat(created.getUuid()).isNotBlank();
        assertThat(created.getVersion()).isZero();
        assertThat(created.isEnabled()).isTrue();
        verify(ruleLibRepository).insert(created);
    }

    @Test
    @DisplayName("创建：interface 型必须提供 interface_code")
    void create_interface_ok() {
        RuleLibEntry created = service.create(new RuleCreateRequest(
                "外部校验", "interface", null, "check_api", false, null));
        assertThat(created.isEnabled()).isFalse();
        assertThat(created.getInterfaceCode()).isEqualTo("check_api");

        assertThatThrownBy(() -> service.create(new RuleCreateRequest(
                "缺接口", "interface", null, " ", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("创建：载荷校验分支（expression 缺失 40001/超长 40002/类型非法 40002）")
    void create_payload_validation() {
        assertThatThrownBy(() -> service.create(new RuleCreateRequest(
                "缺表达式", "expression", " ", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);

        String tooLong = "a".repeat(RuleSpec.MAX_EXPRESSION_LENGTH + 1);
        assertThatThrownBy(() -> service.create(new RuleCreateRequest(
                "超长", "expression", tooLong, null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40002);

        assertThatThrownBy(() -> service.create(new RuleCreateRequest(
                "非法类型", "script", "#x", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40002);
    }

    @Test
    @DisplayName("创建：uuid 冲突兜底 40006")
    void create_insertRace_40006() {
        doThrow(new TsConditionCheckException("condition check fail"))
                .when(ruleLibRepository).insert(any());
        assertThatThrownBy(() -> service.create(new RuleCreateRequest(
                "冲突", "expression", "#x", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40006);
    }

    @Test
    @DisplayName("修改：version+1 + 保存即时失效（DEL + 广播）；未命中 40401")
    void update() {
        when(ruleLibRepository.findByUuid("r1")).thenReturn(Optional.of(entry("r1", 0)));
        RuleLibEntry updated = service.update("r1", new RuleUpdateRequest(
                "新名", "expression", "#amount < 100", null, false, "d"));
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.isEnabled()).isFalse();
        verify(ruleLibRepository).updateWithVersion(updated);
        verify(cacheService).invalidateShared(CacheKeyBuilder.rule("r1"));
        verify(publisher).publish(any(SchemaInvalidationEvent.class));

        when(ruleLibRepository.findByUuid("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("none", new RuleUpdateRequest(
                "n", "expression", "#x", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("删除：被绑定 40010 且 bound_docs 汇总 create_rules 与 status_rules 引用")
    void delete_bound_40010() {
        when(ruleLibRepository.findByUuid("r1")).thenReturn(Optional.of(entry("r1", 0)));
        DocRuleConfig byCreate = new DocRuleConfig();
        byCreate.setDocCode("po");
        byCreate.setCreateRules(List.of("r1"));
        DocRuleConfig byStatus = new DocRuleConfig();
        byStatus.setDocCode("so");
        byStatus.setStatusRules(List.of(Map.of("status", 10L, "rules", List.of("r1"))));
        when(docRuleConfigRepository.findAll()).thenReturn(List.of(byCreate, byStatus));

        assertThatThrownBy(() -> service.delete("r1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40010);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    assertThat((List<String>) data.get("bound_docs")).containsExactlyInAnyOrder("po", "so");
                });
        verify(ruleLibRepository, never()).deleteByUuidIfVersion(anyString(), anyLong());
    }

    @Test
    @DisplayName("删除：无绑定条件删除成功 + 失效；条件失败 40901")
    void delete_ok_and_conflict() {
        when(ruleLibRepository.findByUuid("r1")).thenReturn(Optional.of(entry("r1", 0)));
        when(docRuleConfigRepository.findAll()).thenReturn(List.of());
        service.delete("r1");
        verify(ruleLibRepository).deleteByUuidIfVersion("r1", 0L);
        verify(cacheService).invalidateShared(CacheKeyBuilder.rule("r1"));

        when(ruleLibRepository.findByUuid("raced")).thenReturn(Optional.of(entry("raced", 2)));
        doThrow(new TsConditionCheckException("condition check fail"))
                .when(ruleLibRepository).deleteByUuidIfVersion("raced", 2L);
        assertThatThrownBy(() -> service.delete("raced"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    @Test
    @DisplayName("详情/列表：未命中 40401；关键字与启用状态过滤")
    void detail_and_list() {
        when(ruleLibRepository.findByUuid("r1")).thenReturn(Optional.of(entry("r1", 0)));
        assertThat(service.detail("r1").getUuid()).isEqualTo("r1");
        when(ruleLibRepository.findByUuid("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);

        RuleLibEntry enabled = entry("金额", 0);
        enabled.setRuleName("金额校验");
        enabled.setUpdateTime(2L);
        RuleLibEntry disabled = entry("其他", 0);
        disabled.setRuleName("其他规则");
        disabled.setEnabled(false);
        disabled.setUpdateTime(1L);
        when(ruleLibRepository.findAll()).thenReturn(List.of(enabled, disabled));
        assertThat(service.list(null, true)).hasSize(1);
        assertThat(service.list("金额", null)).hasSize(1);
        assertThat(service.list(null, null)).hasSize(2)
                .extracting(RuleLibEntry::getUuid).containsExactly("金额", "其他");
    }

    // ==================== RE-02 在线测试 ====================

    @Test
    @DisplayName("在线测试：与运行时同一执行器；reason 为空兜底空串")
    void test_online() {
        when(ruleLibRepository.findByUuid("r1")).thenReturn(Optional.of(entry("r1", 0)));
        RuleSpec spec = new RuleSpec("r1", "r1-name", "expression", "#amount > 0", true);
        when(ruleExecutor.test(any(RuleSpec.class), anyString()))
                .thenReturn(RuleEvalResult.pass(spec));

        Map<String, Object> result = service.test("r1",
                new RuleTestRequest(Map.of("attributes", Map.of("amount", 10))));
        assertThat(result.get("passed")).isEqualTo(true);
        assertThat(result.get("skipped")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("");
        assertThat(result.get("rule_uuid")).isEqualTo("r1");

        when(ruleExecutor.test(any(RuleSpec.class), anyString()))
                .thenReturn(RuleEvalResult.fail(spec, "表达式求值 false"));
        Map<String, Object> failed = service.test("r1", null);
        assertThat(failed.get("passed")).isEqualTo(false);
        assertThat(failed.get("reason")).isEqualTo("表达式求值 false");
    }

    // ==================== 运行侧契约 ====================

    @Test
    @DisplayName("getPublishedDto：缓存命中/空值占位/回源回填/未命中 40401")
    void getPublishedDto_paths() throws Exception {
        RuleLibDTO dto = DocDtoMapper.toDto(entry("r1", 1));
        when(cacheService.get(CacheKeyBuilder.rule("r1")))
                .thenReturn(objectMapper.writeValueAsString(dto));
        assertThat(service.getPublishedDto("r1").getUuid()).isEqualTo("r1");

        when(cacheService.get(CacheKeyBuilder.rule("null")))
                .thenReturn(CacheService.NULL_PLACEHOLDER);
        assertThatThrownBy(() -> service.getPublishedDto("null"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);

        when(cacheService.get(CacheKeyBuilder.rule("r2"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("token");
        when(ruleLibRepository.findByUuid("r2")).thenReturn(Optional.of(entry("r2", 0)));
        assertThat(service.getPublishedDto("r2").getUuid()).isEqualTo("r2");
        verify(cacheService).finishRebuild(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());

        when(cacheService.get(CacheKeyBuilder.rule("gone"))).thenReturn(null);
        when(ruleLibRepository.findByUuid("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublishedDto("gone"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
        verify(cacheService).finishRebuildNull(org.mockito.ArgumentMatchers.eq(CacheKeyBuilder.rule("gone")), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true));
    }
}
