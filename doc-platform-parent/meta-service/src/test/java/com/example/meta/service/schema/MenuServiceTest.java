package com.example.meta.service.schema;

import java.util.List;
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
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntitySchemaRepository;
import com.example.meta.domain.schema.EntityStatus;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.domain.schema.MenuEntry;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.meta.web.dto.MenuMountRequest;
import com.example.meta.web.dto.MenuMountedView;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MenuService EC-05 业务测试（全 Mockito）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuServiceTest {

    @Mock
    private MenuConfigRepository menuConfigRepository;
    @Mock
    private EntitySchemaRepository schemaRepository;
    @Mock
    private DocSchemaDefinition.Repository docSchemaRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private SchemaInvalidationPublisher publisher;
    @Mock
    private ConfigAuditService auditService;

    private MenuService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new MenuService(menuConfigRepository, schemaRepository, docSchemaRepository,
                cacheService, publisher, objectMapper, auditService);
    }

    private MenuEntry entry(String targetType, String targetCode, String group,
                            long sortOrder, boolean enabled) {
        MenuEntry entry = new MenuEntry();
        entry.setTargetType(targetType);
        entry.setTargetCode(targetCode);
        entry.setMenuName(targetCode + "-menu");
        entry.setGroupCode(group);
        entry.setSortOrder(sortOrder);
        entry.setEnabled(enabled);
        return entry;
    }

    private DocSchemaDefinition docDefinition(String code, long status) {
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setCode(code);
        definition.setName(code);
        definition.setStatus(status);
        return definition;
    }

    private EntitySchema schema(String code, EntityStatus status) {
        EntitySchema schema = new EntitySchema();
        schema.setCode(code);
        schema.setName(code);
        schema.setStatus(status);
        schema.setVersion(1L);
        return schema;
    }

    @Test
    @DisplayName("挂载：非法目标类型抛 40002")
    void mount_invalidType() {
        assertThatThrownBy(() -> service.mount("unknown", "x", new MenuMountRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40002);
    }

    @Test
    @DisplayName("挂载：entity 目标不存在抛 40401")
    void mount_targetNotFound() {
        when(schemaRepository.findByCode("none")).thenReturn(Optional.empty());
        MenuMountRequest request = new MenuMountRequest();
        request.setMenuName("客户");
        assertThatThrownBy(() -> service.mount("entity", "none", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("挂载成功：缺省分组 basic_data + 全局版本自增 + evict + 广播")
    void mount_success_fullChain() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED)));
        when(menuConfigRepository.findByTarget("entity", "customer")).thenReturn(Optional.empty());
        when(menuConfigRepository.incrementSchemaVersion()).thenReturn(7L);

        MenuMountRequest request = new MenuMountRequest();
        request.setMenuName("客户");
        MenuEntry saved = service.mount("entity", "customer", request);

        assertThat(saved.getGroupCode()).isEqualTo("basic_data");
        assertThat(saved.isEnabled()).isTrue();
        verify(menuConfigRepository).save(saved);
        verify(cacheService).invalidateShared(CacheKeyBuilder.menuTree());
        ArgumentCaptor<SchemaInvalidationEvent> captor =
                ArgumentCaptor.forClass(SchemaInvalidationEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().getCacheDomain()).isEqualTo("menu_config");
        assertThat(captor.getValue().getCode()).isEqualTo("entity_customer");
        assertThat(captor.getValue().getVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("mounted：仅已挂载且已发布入列；分组排序；携带全局版本")
    void mounted_filtersAndGroups() {
        when(cacheService.get(CacheKeyBuilder.menuTree())).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("lock-token");
        when(menuConfigRepository.getSchemaVersion()).thenReturn(5L);
        when(menuConfigRepository.findAll()).thenReturn(List.of(
                entry("entity", "customer", "basic_data", 2L, true),
                entry("entity", "product", "basic_data", 1L, true),
                entry("entity", "draft_entity", "basic_data", 3L, true),
                entry("entity", "offline_entity", "basic_data", 4L, false),
                entry("doc", "sales_order", "biz_doc", 1L, true)));
        when(docSchemaRepository.findByCode("sales_order"))
                .thenReturn(Optional.of(docDefinition("sales_order", DocSchemaDefinition.STATUS_PUBLISHED)));
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED)));
        when(schemaRepository.findByCode("product"))
                .thenReturn(Optional.of(schema("product", EntityStatus.PUBLISHED)));
        when(schemaRepository.findByCode("draft_entity"))
                .thenReturn(Optional.of(schema("draft_entity", EntityStatus.DRAFT)));

        MenuMountedView view = service.mounted();

        assertThat(view.getSchemaVersion()).isEqualTo(5L);
        // 扁平 items：分组固定顺序 basic_data→biz_doc，组内 sortOrder 升序；草稿与未挂载条目被过滤
        assertThat(view.getItems())
                .extracting(MenuMountedView.MenuItem::getCode)
                .containsExactly("product", "customer", "sales_order");
        assertThat(view.getItems())
                .extracting(MenuMountedView.MenuItem::getGroupCode)
                .containsExactly("basic_data", "basic_data", "biz_doc");
        verify(cacheService).finishRebuild(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("mounted：doc 已发布条目出列（obj_type=doc、biz_doc 分组，同 entity 口径）")
    void mounted_docPublished_listed() {
        when(cacheService.get(CacheKeyBuilder.menuTree())).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("lock-token");
        when(menuConfigRepository.getSchemaVersion()).thenReturn(5L);
        when(menuConfigRepository.findAll()).thenReturn(List.of(
                entry("doc", "export_order", "biz_doc", 1L, true)));
        when(docSchemaRepository.findByCode("export_order"))
                .thenReturn(Optional.of(docDefinition("export_order", DocSchemaDefinition.STATUS_PUBLISHED)));

        MenuMountedView view = service.mounted();

        assertThat(view.getItems()).hasSize(1);
        MenuMountedView.MenuItem item = view.getItems().get(0);
        assertThat(item.getCode()).isEqualTo("export_order");
        assertThat(item.getObjType()).isEqualTo("doc");
        assertThat(item.getGroupCode()).isEqualTo("biz_doc");
        assertThat(item.getMenuName()).isEqualTo("export_order-menu");
        assertThat(item.getSortOrder()).isEqualTo(1L);
    }

    @Test
    @DisplayName("mounted：doc 未发布（草稿/停用）条目不出列")
    void mounted_docUnpublished_filtered() {
        when(cacheService.get(CacheKeyBuilder.menuTree())).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("lock-token");
        when(menuConfigRepository.getSchemaVersion()).thenReturn(5L);
        when(menuConfigRepository.findAll()).thenReturn(List.of(
                entry("doc", "draft_doc", "biz_doc", 1L, true)));
        when(docSchemaRepository.findByCode("draft_doc"))
                .thenReturn(Optional.of(docDefinition("draft_doc", DocSchemaDefinition.STATUS_DRAFT)));

        assertThat(service.mounted().getItems()).isEmpty();
    }

    @Test
    @DisplayName("mounted：doc 定义不存在条目不出列")
    void mounted_docUndefined_filtered() {
        when(cacheService.get(CacheKeyBuilder.menuTree())).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn("lock-token");
        when(menuConfigRepository.getSchemaVersion()).thenReturn(5L);
        when(menuConfigRepository.findAll()).thenReturn(List.of(
                entry("doc", "ghost_doc", "biz_doc", 1L, true)));
        when(docSchemaRepository.findByCode("ghost_doc")).thenReturn(Optional.empty());

        assertThat(service.mounted().getItems()).isEmpty();
    }

    @Test
    @DisplayName("挂载：doc 目标不存在抛 40401")
    void mount_docTargetNotFound() {
        when(docSchemaRepository.findByCode("none")).thenReturn(Optional.empty());
        MenuMountRequest request = new MenuMountRequest();
        request.setMenuName("外销订单");
        assertThatThrownBy(() -> service.mount("doc", "none", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40401);
    }

    @Test
    @DisplayName("挂载：显式分组码不在固定值域（basic_data/biz_doc/ops）抛 40002")
    void mount_invalidGroupCode() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED)));
        MenuMountRequest request = new MenuMountRequest();
        request.setMenuName("客户");
        request.setGroupCode("base_data");
        assertThatThrownBy(() -> service.mount("entity", "customer", request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40002);
    }

    @Test
    @DisplayName("mounted：缓存命中直接反序列化返回")
    void mounted_cacheHit() throws Exception {
        MenuMountedView cached = new MenuMountedView();
        cached.setSchemaVersion(9L);
        when(cacheService.get(CacheKeyBuilder.menuTree()))
                .thenReturn(objectMapper.writeValueAsString(cached));
        assertThat(service.mounted().getSchemaVersion()).isEqualTo(9L);
    }

    @Test
    @DisplayName("mounted：空值占位返回空视图")
    void mounted_nullPlaceholder() {
        when(cacheService.get(CacheKeyBuilder.menuTree()))
                .thenReturn(CacheService.NULL_PLACEHOLDER);
        assertThat(service.mounted().getItems()).isEmpty();
    }

    @Test
    @DisplayName("mounted：未获锁轮询超限后直读回源（不回填）")
    void mounted_lockMiss_directRead() {
        when(cacheService.get(CacheKeyBuilder.menuTree())).thenReturn(null);
        when(cacheService.tryRebuildLock(anyString())).thenReturn(null);
        when(cacheService.rebuildMaxWaits()).thenReturn(1);
        when(cacheService.rebuildWaitMillis()).thenReturn(0L);
        when(menuConfigRepository.findAll()).thenReturn(List.of());
        MenuMountedView view = service.mounted();
        assertThat(view.getItems()).isEmpty();
        org.mockito.Mockito.verify(cacheService, org.mockito.Mockito.never())
                .finishRebuild(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("warmup 写入两级缓存")
    void warmup() {
        when(menuConfigRepository.findAll()).thenReturn(List.of());
        service.warmup();
        verify(cacheService).putShared(anyString(), anyString());
    }

    @Test
    @DisplayName("全局版本自增失败降级读取当前版本（不阻断挂载）")
    void mount_versionBumpFailure_degrade() {
        when(schemaRepository.findByCode("customer"))
                .thenReturn(Optional.of(schema("customer", EntityStatus.PUBLISHED)));
        when(menuConfigRepository.findByTarget("entity", "customer")).thenReturn(Optional.empty());
        when(menuConfigRepository.incrementSchemaVersion())
                .thenThrow(new RuntimeException("conflict"));
        when(menuConfigRepository.getSchemaVersion()).thenReturn(3L);

        MenuMountRequest request = new MenuMountRequest();
        request.setMenuName("客户");
        assertThat(service.mount("entity", "customer", request).getMenuName()).isEqualTo("客户");
    }
}
