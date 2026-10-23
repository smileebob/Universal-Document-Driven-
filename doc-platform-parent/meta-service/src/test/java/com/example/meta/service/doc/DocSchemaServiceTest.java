package com.example.meta.service.doc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.common.exception.BizException;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.infrastructure.index.DocDataIndexRegistrar;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.web.dto.DocSchemaCreateRequest;
import com.example.meta.web.dto.DocSchemaUpdateRequest;
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
 * DocSchemaService DC-01~06 业务测试（全 Mockito）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocSchemaServiceTest {

    @Mock
    private DocSchemaDefinition.Repository docSchemaRepository;
    @Mock
    private MenuConfigRepository menuConfigRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private SchemaInvalidationPublisher publisher;
    @Mock
    private DocDataIndexRegistrar docDataIndexRegistrar;
    @Mock
    private ConfigAuditService configAuditService;

    private DocSchemaService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new DocSchemaService(docSchemaRepository, menuConfigRepository,
                cacheService, publisher, docDataIndexRegistrar, objectMapper, configAuditService);
        when(menuConfigRepository.incrementSchemaVersion()).thenReturn(1L);
    }

    private DocSchemaCreateRequest createRequest(String code, String name) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        Map<String, Object> attr = new HashMap<>();
        attr.put("code", "order_no");
        attr.put("name", "订单号");
        attr.put("data_type", "string");
        attributes.add(attr);
        return new DocSchemaCreateRequest(code, name, "biz", "desc", attributes, null);
    }

    private DocSchemaDefinition definition(String code, long status, long version) {
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setUuid("uuid-" + code);
        definition.setCode(code);
        definition.setName(code + "-name");
        definition.setDocType("biz");
        definition.setStatus(status);
        definition.setVersion(version);
        Map<String, Object> attr = new HashMap<>();
        attr.put("code", "order_no");
        attr.put("name", "订单号");
        attr.put("data_type", "string");
        definition.setAttributes(new ArrayList<>(List.of(attr)));
        definition.setStatusDefs(new ArrayList<>(List.of(
                Map.of("status", 100L, "name", "暂存"),
                Map.of("status", 99L, "name", "已关闭"))));
        return definition;
    }

    // ==================== DC-01/02 ====================

    @Test
    @DisplayName("创建：DRAFT version=0，缺省五状态字典落库")
    void create_draft_defaultStatusDefs() {
        when(docSchemaRepository.findByCode("po")).thenReturn(Optional.empty());
        when(docSchemaRepository.findAll()).thenReturn(List.of());

        DocSchemaDefinition created = service.create(createRequest("po", "外销订单"));

        assertThat(created.getStatus()).isEqualTo(DocSchemaDefinition.STATUS_DRAFT);
        assertThat(created.getVersion()).isZero();
        assertThat(created.getStatusDefs()).hasSize(5);
        verify(docSchemaRepository).insert(created);
        verify(menuConfigRepository).incrementSchemaVersion();
    }

    @Test
    @DisplayName("创建：编码重复抛 40006；名称重复抛 40006")
    void create_duplicate_40006() {
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_DRAFT, 0)));
        assertThatThrownBy(() -> service.create(createRequest("po", "外销订单")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40006);

        when(docSchemaRepository.findByCode("po2")).thenReturn(Optional.empty());
        DocSchemaDefinition existing = definition("other", DocSchemaDefinition.STATUS_DRAFT, 0);
        existing.setName("外销订单");
        when(docSchemaRepository.findAll()).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.create(createRequest("po2", "外销订单")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40006);
    }

    @Test
    @DisplayName("创建：EXPECT_NOT_EXIST 竞态兜底 40006")
    void create_insertRace_40006() {
        when(docSchemaRepository.findByCode("po")).thenReturn(Optional.empty());
        when(docSchemaRepository.findAll()).thenReturn(List.of());
        doThrow(new TsConditionCheckException("condition check fail"))
                .when(docSchemaRepository).insert(any());
        assertThatThrownBy(() -> service.create(createRequest("po", "外销订单")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40006);
    }

    @Test
    @DisplayName("修改：非草稿抛 40007；草稿修改成功 version+1（同名排除自身）")
    void update_editable() {
        DocSchemaUpdateRequest request = new DocSchemaUpdateRequest(
                "new-name", "biz", "desc", List.of(), null);

        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_PUBLISHED, 1)));
        assertThatThrownBy(() -> service.update("po", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40007);

        DocSchemaDefinition draft = definition("po", DocSchemaDefinition.STATUS_DRAFT, 0);
        draft.setName("new-name");
        when(docSchemaRepository.findByCode("po")).thenReturn(Optional.of(draft));
        when(docSchemaRepository.findAll()).thenReturn(List.of(draft));
        DocSchemaDefinition updated = service.update("po", request);
        assertThat(updated.getVersion()).isEqualTo(1L);
        verify(docSchemaRepository).updateWithVersion(updated);
    }

    @Test
    @DisplayName("修改：名称与他单重复抛 40006")
    void update_nameDuplicate_40006() {
        DocSchemaUpdateRequest request = new DocSchemaUpdateRequest(
                "冲突名", "biz", "desc", List.of(), null);
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_DRAFT, 0)));
        DocSchemaDefinition other = definition("other", DocSchemaDefinition.STATUS_DRAFT, 0);
        other.setName("冲突名");
        when(docSchemaRepository.findAll()).thenReturn(List.of(other));
        assertThatThrownBy(() -> service.update("po", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40006);
    }

    @Test
    @DisplayName("创建：属性展示字段补默认落库（design/02 §二口径，table 明细同层递归）")
    void create_attributesDisplayDefaultsNormalized() {
        when(docSchemaRepository.findByCode("po")).thenReturn(Optional.empty());
        when(docSchemaRepository.findAll()).thenReturn(List.of());

        // 仅携带必要字段，展示字段全部缺省（旧链路缺陷复现场景）
        Map<String, Object> amount = new HashMap<>();
        amount.put("code", "total_amount");
        amount.put("name", "订单总额");
        amount.put("data_type", "decimal");
        // 显式 visible=false 必须原样保留（默认值不得覆盖显式值）
        Map<String, Object> hidden = new HashMap<>();
        hidden.put("code", "erp_code");
        hidden.put("name", "ERP编码");
        hidden.put("data_type", "string");
        hidden.put("visible", false);
        // table 明细：sub_attributes 子项同样缺省
        Map<String, Object> items = new HashMap<>();
        items.put("code", "items");
        items.put("name", "明细");
        items.put("data_type", "table");
        Map<String, Object> sub = new HashMap<>();
        sub.put("code", "qty");
        sub.put("name", "数量");
        sub.put("data_type", "decimal");
        items.put("sub_attributes", new ArrayList<>(List.of(sub)));

        DocSchemaDefinition created = service.create(new DocSchemaCreateRequest(
                "po", "外销订单", "biz", "desc",
                new ArrayList<>(List.of(amount, hidden, items)), null));

        assertThat(created.getAttributes().get(0))
                .containsEntry("required", false)
                .containsEntry("visible", true)
                .containsEntry("editable", true)
                .containsEntry("is_list_column", false)
                .containsEntry("is_searchable", false)
                .containsEntry("unique", false);
        assertThat(created.getAttributes().get(1)).containsEntry("visible", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subs =
                (List<Map<String, Object>>) created.getAttributes().get(2).get("sub_attributes");
        assertThat(subs).hasSize(1);
        assertThat(subs.get(0))
                .containsEntry("visible", true)
                .containsEntry("editable", true)
                .containsEntry("is_list_column", false)
                .containsEntry("is_searchable", false);
    }

    // ==================== DC-03 发布/停用 ====================

    @Test
    @DisplayName("发布成功：状态/版本/发布时间落值 + 失效链（DEL + 广播）+ 配置审计")
    void publish_success_fullChain() {
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_DRAFT, 0)));

        DocSchemaDefinition published = service.publish("po");

        assertThat(published.getStatus()).isEqualTo(DocSchemaDefinition.STATUS_PUBLISHED);
        assertThat(published.getVersion()).isEqualTo(1L);
        assertThat(published.getPublishedAt()).isNotNull();
        verify(docSchemaRepository).updateWithVersion(published);
        verify(docDataIndexRegistrar).registerQuietly(published.getAttributes());
        verify(cacheService).invalidateShared(CacheKeyBuilder.docSchema("po"));
        ArgumentCaptor<SchemaInvalidationEvent> captor =
                ArgumentCaptor.forClass(SchemaInvalidationEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().getCacheDomain()).isEqualTo("doc_schema");
        assertThat(captor.getValue().getCode()).isEqualTo("po");
        verify(configAuditService).recordConfig(eq("doc_schema"), eq("po"), eq("publish"), any(), any());
    }

    @Test
    @DisplayName("发布：完整性校验失败抛 40011 且 reasons 汇总全部分支")
    void publish_invalid_40011_reasons() {
        DocSchemaDefinition invalid = definition("bad", DocSchemaDefinition.STATUS_DRAFT, 0);
        invalid.setName("  ");
        List<Map<String, Object>> attributes = new ArrayList<>();
        Map<String, Object> blankCode = new HashMap<>();
        blankCode.put("name", "无编码");
        blankCode.put("data_type", "string");
        attributes.add(blankCode);
        Map<String, Object> dup1 = new HashMap<>();
        dup1.put("code", "uuid");
        dup1.put("name", "保留列");
        dup1.put("data_type", "string");
        attributes.add(dup1);
        Map<String, Object> dup2 = new HashMap<>();
        dup2.put("code", "uuid");
        dup2.put("data_type", "unknown_type");
        attributes.add(dup2);
        Map<String, Object> table = new HashMap<>();
        table.put("code", "lines");
        table.put("name", "明细");
        table.put("data_type", "table");
        attributes.add(table);
        Map<String, Object> ref = new HashMap<>();
        ref.put("code", "cust");
        ref.put("name", "客户");
        ref.put("data_type", "ref");
        attributes.add(ref);
        invalid.setAttributes(attributes);
        when(docSchemaRepository.findByCode("bad")).thenReturn(Optional.of(invalid));

        assertThatThrownBy(() -> service.publish("bad"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40011);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    @SuppressWarnings("unchecked")
                    List<String> reasons = (List<String>) data.get("reasons");
                    assertThat(reasons)
                            .anyMatch(r -> r.contains("单据名称不可为空"))
                            .anyMatch(r -> r.contains("属性编码为空"))
                            .anyMatch(r -> r.contains("保留列"))
                            .anyMatch(r -> r.contains("属性编码重复"))
                            .anyMatch(r -> r.contains("数据类型非法"))
                            .anyMatch(r -> r.contains("sub_attributes"))
                            .anyMatch(r -> r.contains("ref_entity_code"))
                            .anyMatch(r -> r.contains("名称不可为空"));
                });
        verify(docSchemaRepository, never()).updateWithVersion(any());
    }

    @Test
    @DisplayName("发布：status_defs 缺失/非法汇总 reasons（40011）")
    void publish_statusDefs_invalid_40011() {
        DocSchemaDefinition invalid = definition("sd", DocSchemaDefinition.STATUS_DRAFT, 0);
        invalid.setStatusDefs(new ArrayList<>(List.of(
                Map.of("status", 10L),
                Map.of("status", "not_number", "name", "非法"),
                Map.of("status", 10L, "name", "重复"))));
        when(docSchemaRepository.findByCode("sd")).thenReturn(Optional.of(invalid));

        assertThatThrownBy(() -> service.publish("sd"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40011);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    @SuppressWarnings("unchecked")
                    List<String> reasons = (List<String>) data.get("reasons");
                    assertThat(reasons)
                            .anyMatch(r -> r.contains("缺失 status"))
                            .anyMatch(r -> r.contains("缺失名称"))
                            .anyMatch(r -> r.contains("状态重复"))
                            .anyMatch(r -> r.contains("必须包含 100"))
                            .anyMatch(r -> r.contains("必须包含 99"));
                });

        // status_defs 整体为空
        invalid.setStatusDefs(null);
        assertThatThrownBy(() -> service.publish("sd"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40011);
    }

    @Test
    @DisplayName("发布：非草稿抛 40901")
    void publish_nonDraft_40901() {
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_PUBLISHED, 1)));
        assertThatThrownBy(() -> service.publish("po"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    @Test
    @DisplayName("停用成功：失效链 + 审计；非已发布抛 40901")
    void disable_success_and_conflict() {
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_PUBLISHED, 1)));
        DocSchemaDefinition disabled = service.disable("po");
        assertThat(disabled.getStatus()).isEqualTo(DocSchemaDefinition.STATUS_DISABLED);
        assertThat(disabled.getVersion()).isEqualTo(2L);
        verify(cacheService).invalidateShared(CacheKeyBuilder.docSchema("po"));
        verify(publisher).publish(any(SchemaInvalidationEvent.class));
        verify(configAuditService).recordConfig(eq("doc_schema"), eq("po"), eq("disable"), any(), any());

        when(docSchemaRepository.findByCode("draft"))
                .thenReturn(Optional.of(definition("draft", DocSchemaDefinition.STATUS_DRAFT, 0)));
        assertThatThrownBy(() -> service.disable("draft"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    // ==================== 删除/查询 ====================

    @Test
    @DisplayName("删除：仅草稿可删（条件删除），否则 40009；条件失败 40901")
    void delete_draftOnly() {
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_DRAFT, 0)));
        service.delete("po");
        verify(docSchemaRepository).deleteByCodeIfDraft("po", 0L);

        when(docSchemaRepository.findByCode("pub"))
                .thenReturn(Optional.of(definition("pub", DocSchemaDefinition.STATUS_PUBLISHED, 1)));
        assertThatThrownBy(() -> service.delete("pub"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40009);

        when(docSchemaRepository.findByCode("raced"))
                .thenReturn(Optional.of(definition("raced", DocSchemaDefinition.STATUS_DRAFT, 2)));
        doThrow(new TsConditionCheckException("condition check fail"))
                .when(docSchemaRepository).deleteByCodeIfDraft("raced", 2L);
        assertThatThrownBy(() -> service.delete("raced"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    @Test
    @DisplayName("详情：命中返回；未命中 40401")
    void detail() {
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_DRAFT, 0)));
        assertThat(service.detail("po").getCode()).isEqualTo("po");
        when(docSchemaRepository.findByCode("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("列表：status 数值筛选 + 关键字过滤 + update_time 倒序")
    void list_filter() {
        DocSchemaDefinition draft = definition("aaa", DocSchemaDefinition.STATUS_DRAFT, 0);
        draft.setUpdateTime(1L);
        DocSchemaDefinition published = definition("bbb", DocSchemaDefinition.STATUS_PUBLISHED, 1);
        published.setUpdateTime(2L);
        when(docSchemaRepository.findAll()).thenReturn(List.of(draft, published));

        assertThat(service.list(100, null)).hasSize(1);
        assertThat(service.list(null, "bbb")).hasSize(1);
        assertThat(service.list(null, "  ")).hasSize(2);
        assertThat(service.list(null, null)).hasSize(2)
                .extracting(DocSchemaDefinition::getCode).containsExactly("bbb", "aaa");
    }

    // ==================== 缓存回源 ====================

    @Test
    @DisplayName("getPublishedDto：缓存命中直接返回")
    void getPublishedDto_cacheHit() throws Exception {
        DocSchemaDTO dto = DocDtoMapper.toDto(
                definition("po", DocSchemaDefinition.STATUS_PUBLISHED, 1));
        when(cacheService.get(CacheKeyBuilder.docSchema("po")))
                .thenReturn(objectMapper.writeValueAsString(dto));
        assertThat(service.getPublishedDto("po").getCode()).isEqualTo("po");
        verify(docSchemaRepository, never()).findByCode(anyString());
    }

    @Test
    @DisplayName("getPublishedDto：空值占位抛 40401；坏 JSON 直读回源")
    void getPublishedDto_nullPlaceholder_and_corrupt() {
        when(cacheService.get(CacheKeyBuilder.docSchema("po")))
                .thenReturn(CacheService.NULL_PLACEHOLDER);
        assertThatThrownBy(() -> service.getPublishedDto("po"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);

        when(cacheService.get(CacheKeyBuilder.docSchema("bad_json")))
                .thenReturn("{corrupt");
        when(docSchemaRepository.findByCode("bad_json"))
                .thenReturn(Optional.of(definition("bad_json",
                        DocSchemaDefinition.STATUS_PUBLISHED, 1)));
        assertThat(service.getPublishedDto("bad_json").getCode()).isEqualTo("bad_json");

        when(cacheService.get(CacheKeyBuilder.docSchema("gone")))
                .thenReturn("{corrupt");
        when(docSchemaRepository.findByCode("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublishedDto("gone"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("getPublishedDto：miss 获锁回源回填；未发布写空值占位并 40401")
    void getPublishedDto_rebuild_and_null() {
        when(cacheService.get(CacheKeyBuilder.docSchema("po"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("lock-token");
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_PUBLISHED, 1)));

        assertThat(service.getPublishedDto("po").getCode()).isEqualTo("po");
        verify(cacheService).finishRebuild(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(cacheService).releaseRebuildLock(anyString(), anyString());

        when(cacheService.get(CacheKeyBuilder.docSchema("draft_only"))).thenReturn(null);
        when(docSchemaRepository.findByCode("draft_only"))
                .thenReturn(Optional.of(definition("draft_only", DocSchemaDefinition.STATUS_DRAFT, 0)));
        assertThatThrownBy(() -> service.getPublishedDto("draft_only"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
        verify(cacheService).finishRebuildNull(org.mockito.ArgumentMatchers.eq(CacheKeyBuilder.docSchema("draft_only")), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    @DisplayName("getPublishedDto：未获锁轮询命中缓存 / 超限直读回源（不回填）")
    void getPublishedDto_waitThenDirectRead() {
        when(cacheService.get(CacheKeyBuilder.docSchema("po"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn(null);
        when(cacheService.rebuildMaxWaits()).thenReturn(1);
        when(cacheService.rebuildWaitMillis()).thenReturn(0L);
        when(docSchemaRepository.findByCode("po"))
                .thenReturn(Optional.of(definition("po", DocSchemaDefinition.STATUS_PUBLISHED, 1)));
        assertThat(service.getPublishedDto("po").getCode()).isEqualTo("po");
        verify(cacheService, never()).finishRebuild(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());

        // 轮询期间缓存被他人回填 → 直接命中返回
        when(cacheService.get(CacheKeyBuilder.docSchema("po2")))
                .thenReturn(null)
                .thenReturn("{\"code\":\"po2\"}");
        assertThat(service.getPublishedDto("po2").getCode()).isEqualTo("po2");

        // 未获锁且回源无已发布定义 → 40401
        when(cacheService.get(CacheKeyBuilder.docSchema("none"))).thenReturn(null);
        when(docSchemaRepository.findByCode("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublishedDto("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("listPublishedDtos 与 warmup 写入两级缓存")
    void listPublished_and_warmup() {
        when(docSchemaRepository.findAll()).thenReturn(List.of(
                definition("pub_doc", DocSchemaDefinition.STATUS_PUBLISHED, 1),
                definition("draft_doc", DocSchemaDefinition.STATUS_DRAFT, 0)));
        assertThat(service.listPublishedDtos()).hasSize(1);
        service.warmup();
        verify(cacheService).putShared(eq(CacheKeyBuilder.docSchema("pub_doc")), anyString());
    }

    @Test
    @DisplayName("全局版本自增失败不阻断业务")
    void bumpGlobalVersion_failure_notBlocking() {
        when(menuConfigRepository.incrementSchemaVersion())
                .thenThrow(new RuntimeException("conflict"));
        when(docSchemaRepository.findByCode("po")).thenReturn(Optional.empty());
        when(docSchemaRepository.findAll()).thenReturn(List.of());
        assertThat(service.create(createRequest("po", "外销订单")).getCode()).isEqualTo("po");
    }
}
