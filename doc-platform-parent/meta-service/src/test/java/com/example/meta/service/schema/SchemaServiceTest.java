package com.example.meta.service.schema;

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
import com.example.meta.api.dto.EntitySchemaDTO;
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
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.infrastructure.index.EntityDataIndexRegistrar;
import com.example.meta.web.dto.AttributeSpec;
import com.example.meta.web.dto.EntityCreateRequest;
import com.example.meta.web.dto.EntityUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SchemaService EC-01~04 业务测试（全 Mockito）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaServiceTest {

    @Mock
    private EntitySchemaRepository schemaRepository;
    @Mock
    private MenuConfigRepository menuConfigRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private SchemaInvalidationPublisher publisher;
    @Mock
    private EntityDataIndexRegistrar indexRegistrar;
    @Mock
    private ConfigAuditService auditService;

    private SchemaService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new SchemaService(schemaRepository, menuConfigRepository, cacheService,
                publisher, indexRegistrar, objectMapper, auditService);
        when(menuConfigRepository.incrementSchemaVersion()).thenReturn(1L);
    }

    private EntityCreateRequest createRequest(String code) {
        EntityCreateRequest request = new EntityCreateRequest();
        request.setEntityCode(code);
        request.setEntityName(code + "-name");
        request.setEntityType("base");
        AttributeSpec spec = new AttributeSpec();
        spec.setCode("attr_a");
        spec.setName("属性A");
        spec.setDataType("string");
        request.setAttributes(List.of(spec));
        return request;
    }

    private EntitySchema schema(String code, EntityStatus status, long version) {
        EntitySchema schema = new EntitySchema();
        schema.setCode(code);
        schema.setName(code + "-name");
        schema.setStatus(status);
        schema.setVersion(version);
        AttributeDef def = new AttributeDef();
        def.setCode("attr_a");
        def.setName("属性A");
        def.setDataType("string");
        schema.setAttributes(List.of(def));
        return schema;
    }

    // ==================== EC-01/02 ====================

    @Test
    @DisplayName("创建：DRAFT version=0，全局版本自增")
    void create_draft() {
        when(schemaRepository.findByCode("customer")).thenReturn(Optional.empty());
        EntitySchema created = service.create(createRequest("customer"));
        assertThat(created.getStatus()).isEqualTo(EntityStatus.DRAFT);
        assertThat(created.getVersion()).isZero();
        verify(schemaRepository).insert(created);
        verify(menuConfigRepository).incrementSchemaVersion();
    }

    @Test
    @DisplayName("创建：编码重复抛 40006")
    void create_duplicate_40006() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.DRAFT, 0)));
        assertThatThrownBy(() -> service.create(createRequest("customer")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40006);
    }

    @Test
    @DisplayName("修改：非 DRAFT 抛 40007；DRAFT 修改成功 version+1")
    void update_editable() {
        EntityUpdateRequest request = new EntityUpdateRequest();
        request.setEntityName("new-name");
        request.setAttributes(List.of());

        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED, 1)));
        assertThatThrownBy(() -> service.update("customer", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40007);

        when(schemaRepository.findByCode("draft_entity"))
                .thenReturn(Optional.of(schema("draft_entity", EntityStatus.DRAFT, 0)));
        EntitySchema updated = service.update("draft_entity", request);
        assertThat(updated.getVersion()).isEqualTo(1L);
        verify(schemaRepository).updateWithVersion(updated);
    }

    // ==================== EC-03 发布 ====================

    @Test
    @DisplayName("发布：校验失败抛 40011 且 data.reasons 汇总")
    void publish_invalid_40011() {
        EntitySchema invalid = schema("bad_entity", EntityStatus.DRAFT, 0);
        invalid.setName(null);
        AttributeDef reserved = new AttributeDef();
        reserved.setCode("uuid");
        reserved.setName("保留列");
        reserved.setDataType("string");
        invalid.setAttributes(List.of(reserved));
        when(schemaRepository.findByCode("bad_entity")).thenReturn(Optional.of(invalid));

        assertThatThrownBy(() -> service.publish("bad_entity"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(40011);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) biz.getData();
                    List<String> reasons = (List<String>) data.get("reasons");
                    assertThat(reasons).hasSizeGreaterThanOrEqualTo(2);
                });
        verify(schemaRepository, never()).updateWithVersion(any());
    }

    @Test
    @DisplayName("发布：ref 目标未发布进入 reasons")
    void publish_refTargetUnpublished_40011() {
        EntitySchema withRef = schema("order_head", EntityStatus.DRAFT, 0);
        AttributeDef ref = new AttributeDef();
        ref.setCode("customer_ref");
        ref.setName("客户");
        ref.setDataType("ref");
        ref.setRefEntityCode("customer_draft");
        withRef.setAttributes(List.of(ref));
        when(schemaRepository.findByCode("order_head")).thenReturn(Optional.of(withRef));
        when(schemaRepository.findByCode("customer_draft"))
                .thenReturn(Optional.of(schema("customer_draft", EntityStatus.DRAFT, 0)));

        assertThatThrownBy(() -> service.publish("order_head"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40011);
    }

    @Test
    @DisplayName("发布成功：状态/版本/发布时间落值 + 索引注册 + 失效链（DEL + 广播）")
    void publish_success_fullChain() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.DRAFT, 0)));

        EntitySchema published = service.publish("customer");

        assertThat(published.getStatus()).isEqualTo(EntityStatus.PUBLISHED);
        assertThat(published.getVersion()).isEqualTo(1L);
        assertThat(published.getPublishedAt()).isNotNull();
        verify(schemaRepository).updateWithVersion(published);
        verify(indexRegistrar).registerQuietly(anyList());
        verify(cacheService).invalidateShared(CacheKeyBuilder.entitySchema("customer"));
        ArgumentCaptor<SchemaInvalidationEvent> captor =
                ArgumentCaptor.forClass(SchemaInvalidationEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().getCacheDomain()).isEqualTo("entity_schema");
        assertThat(captor.getValue().getCode()).isEqualTo("customer");
        assertThat(captor.getValue().getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("发布：非 DRAFT 抛 40901")
    void publish_nonDraft_40901() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED, 1)));
        assertThatThrownBy(() -> service.publish("customer"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    // ==================== EC-03 停用 ====================

    @Test
    @DisplayName("停用成功：级联取消菜单挂载 + 失效链；非已发布抛 40901")
    void disable_success_and_conflict() {
        EntitySchema published = schema("customer", EntityStatus.PUBLISHED, 1);
        when(schemaRepository.findByCode("customer")).thenReturn(Optional.of(published));
        MenuEntry entry = new MenuEntry();
        entry.setTargetType("entity");
        entry.setTargetCode("customer");
        entry.setEnabled(true);
        when(menuConfigRepository.findByTarget("entity", "customer")).thenReturn(Optional.of(entry));

        EntitySchema disabled = service.disable("customer");
        assertThat(disabled.getStatus()).isEqualTo(EntityStatus.DISABLED);
        assertThat(entry.isEnabled()).isFalse();
        verify(menuConfigRepository).save(entry);
        verify(cacheService).invalidateShared(CacheKeyBuilder.menuTree());
        verify(cacheService).invalidateShared(CacheKeyBuilder.entitySchema("customer"));
        verify(publisher).publish(any(SchemaInvalidationEvent.class));

        when(schemaRepository.findByCode("draft_entity"))
                .thenReturn(Optional.of(schema("draft_entity", EntityStatus.DRAFT, 0)));
        assertThatThrownBy(() -> service.disable("draft_entity"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    // ==================== 删除/查询 ====================

    @Test
    @DisplayName("删除：仅草稿可删（条件删除），否则 40009；条件失败 40901")
    void delete_draftOnly() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.DRAFT, 0)));
        service.delete("customer");
        verify(schemaRepository).deleteByCodeIfDraft("customer", 0L);

        when(schemaRepository.findByCode("published_entity"))
                .thenReturn(Optional.of(schema("published_entity", EntityStatus.PUBLISHED, 1)));
        assertThatThrownBy(() -> service.delete("published_entity"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40009);

        // 并发修改/删除：条件删除失败 → 40901（评审修复 Major-6）
        when(schemaRepository.findByCode("raced"))
                .thenReturn(Optional.of(schema("raced", EntityStatus.DRAFT, 2)));
        doThrow(new com.example.common.infrastructure.tablestore.exception.TsConditionCheckException(
                "condition check fail"))
                .when(schemaRepository).deleteByCodeIfDraft("raced", 2L);
        assertThatThrownBy(() -> service.delete("raced"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40901);
    }

    @Test
    @DisplayName("列表：status 数值筛选 + 关键字过滤")
    void list_filter() {
        EntitySchema draft = schema("aaa", EntityStatus.DRAFT, 0);
        draft.setUpdateTime(1L);
        EntitySchema published = schema("bbb", EntityStatus.PUBLISHED, 1);
        published.setUpdateTime(2L);
        when(schemaRepository.findAll()).thenReturn(List.of(draft, published));

        assertThat(service.list(100, null)).hasSize(1);
        assertThat(service.list(null, "bbb")).hasSize(1);
        assertThat(service.list(null, null)).hasSize(2);
    }

    @Test
    @DisplayName("详情未命中抛 40401")
    void detail_notFound() {
        when(schemaRepository.findByCode("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("none"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    // ==================== 缓存回源 ====================

    @Test
    @DisplayName("getPublishedDto：L2/L1 命中直接返回")
    void getPublishedDto_cacheHit() throws Exception {
        EntitySchemaDTO dto = SchemaDtoMapper.toDto(schema("customer", EntityStatus.PUBLISHED, 1));
        when(cacheService.get(CacheKeyBuilder.entitySchema("customer")))
                .thenReturn(objectMapper.writeValueAsString(dto));
        assertThat(service.getPublishedDto("customer").getCode()).isEqualTo("customer");
        verify(schemaRepository, never()).findByCode(anyString());
    }

    @Test
    @DisplayName("getPublishedDto：空值占位抛 40401")
    void getPublishedDto_nullPlaceholder_40401() {
        when(cacheService.get(CacheKeyBuilder.entitySchema("customer")))
                .thenReturn(CacheService.NULL_PLACEHOLDER);
        assertThatThrownBy(() -> service.getPublishedDto("customer"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("getPublishedDto：miss 获锁回源回填；未发布写空值占位并 40401")
    void getPublishedDto_rebuild_and_null() {
        when(cacheService.get(CacheKeyBuilder.entitySchema("customer"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("lock-token");
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED, 1)));

        assertThat(service.getPublishedDto("customer").getCode()).isEqualTo("customer");
        verify(cacheService).finishRebuild(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(cacheService).releaseRebuildLock(anyString(), anyString());

        // 未发布 → 空值占位 + 40401
        when(cacheService.get(CacheKeyBuilder.entitySchema("draft_only"))).thenReturn(null);
        when(schemaRepository.findByCode("draft_only"))
                .thenReturn(Optional.of(schema("draft_only", EntityStatus.DRAFT, 0)));
        assertThatThrownBy(() -> service.getPublishedDto("draft_only"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
        verify(cacheService).finishRebuildNull(org.mockito.ArgumentMatchers.eq(CacheKeyBuilder.entitySchema("draft_only")), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    @DisplayName("getPublishedDto：未获锁轮询后直读回源（不回填）")
    void getPublishedDto_waitThenDirectRead() {
        when(cacheService.get(CacheKeyBuilder.entitySchema("customer"))).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn(null);
        when(cacheService.rebuildMaxWaits()).thenReturn(1);
        when(cacheService.rebuildWaitMillis()).thenReturn(0L);
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED, 1)));

        assertThat(service.getPublishedDto("customer").getCode()).isEqualTo("customer");
        verify(cacheService, never()).finishRebuild(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("listPublishedDtos 与 warmup 写入两级缓存")
    void listPublished_and_warmup() {
        when(schemaRepository.findAll()).thenReturn(List.of(
                schema("pub_entity", EntityStatus.PUBLISHED, 1),
                schema("draft_entity", EntityStatus.DRAFT, 0)));
        assertThat(service.listPublishedDtos()).hasSize(1);
        service.warmup();
        verify(cacheService).putShared(anyString(), anyString());
    }

    @Test
    @DisplayName("全局版本自增失败不阻断业务")
    void bumpGlobalVersion_failure_notBlocking() {
        when(menuConfigRepository.incrementSchemaVersion())
                .thenThrow(new RuntimeException("conflict"));
        when(schemaRepository.findByCode("customer")).thenReturn(Optional.empty());
        assertThat(service.create(createRequest("customer")).getCode()).isEqualTo("customer");
    }
}
