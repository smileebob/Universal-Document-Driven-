package com.example.meta.service.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.constants.CacheDomains;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.messaging.SchemaInvalidationEvent;
import com.example.meta.domain.messaging.SchemaInvalidationPublisher;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntitySchemaRepository;
import com.example.meta.domain.schema.EntityStatus;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.domain.schema.MenuEntry;
import com.example.meta.service.audit.ConfigAuditService;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.cache.CacheService;
import com.example.meta.web.dto.MenuMountRequest;
import com.example.meta.web.dto.MenuMountedView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 菜单挂载服务（EC-05：PUT /api/v1/schema/{type}/{code}/menu + GET /api/v1/menu/mounted）。
 *
 * <p>挂载视图口径（prd/11 §3.4）：按 group_code 分组（basic_data/biz_doc/ops 固定顺序，
 * 未知组追加末尾），组内 sort_order 升序；仅包含已挂载（enabled=1）且对应定义已发布的条目；
 * 响应携带全局 schema_version 版本探针。doc 类菜单按 doc_code 查 doc_schema 定义，
 * 已发布（status=1）即出列，与 entity 口径同构。</p>
 */
@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    /** 合法挂载目标类型 */
    private static final List<String> VALID_TARGET_TYPES = List.of("entity", "doc");

    /** 分组固定顺序（design/03 §2.10） */
    private static final List<String> GROUP_ORDER = List.of("basic_data", "biz_doc", "ops");

    private final MenuConfigRepository menuConfigRepository;
    private final EntitySchemaRepository schemaRepository;
    private final DocSchemaDefinition.Repository docSchemaRepository;
    private final CacheService cacheService;
    private final SchemaInvalidationPublisher invalidationPublisher;
    private final ObjectMapper objectMapper;
    private final ConfigAuditService configAuditService;

    public MenuService(MenuConfigRepository menuConfigRepository,
                       EntitySchemaRepository schemaRepository,
                       DocSchemaDefinition.Repository docSchemaRepository,
                       CacheService cacheService,
                       SchemaInvalidationPublisher invalidationPublisher,
                       ObjectMapper objectMapper,
                       ConfigAuditService configAuditService) {
        this.menuConfigRepository = menuConfigRepository;
        this.schemaRepository = schemaRepository;
        this.docSchemaRepository = docSchemaRepository;
        this.cacheService = cacheService;
        this.invalidationPublisher = invalidationPublisher;
        this.objectMapper = objectMapper;
        this.configAuditService = configAuditService;
    }

    // ==================== EC-05 挂载配置 ====================

    /**
     * 挂载/更新菜单（PUT 幂等；enabled=false 即取消挂载）。
     *
     * <p>失效链：保存 → 全局 schema_version 自增 → evict menuTree → 广播 CONFIG_CHANGED。</p>
     *
     * @param targetType 目标类型（entity/doc）
     * @param targetCode 目标编码
     * @param request    挂载请求
     * @return 保存后的条目
     */
    public MenuEntry mount(String targetType, String targetCode, MenuMountRequest request) {
        if (!VALID_TARGET_TYPES.contains(targetType)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "不支持的挂载目标类型: " + targetType);
        }
        if ("entity".equals(targetType)) {
            // entity 挂载目标必须存在（允许草稿挂载，发布后自动进入 mounted 视图）
            schemaRepository.findByCode(targetCode).orElseThrow(() ->
                    new BizException(ErrorCode.NOT_FOUND, "实体定义不存在: " + targetCode));
        } else {
            // doc 挂载目标必须存在（与 entity 同构；草稿允许挂载，发布后进入 mounted 视图）
            docSchemaRepository.findByCode(targetCode).orElseThrow(() ->
                    new BizException(ErrorCode.NOT_FOUND, "单据定义不存在: " + targetCode));
        }
        long now = System.currentTimeMillis();
        // RD-12 before snapshot（upsert 前捕获，供 JSON Diff）
        MenuEntry existing = menuConfigRepository.findByTarget(targetType, targetCode).orElse(null);
        Map<String, Object> beforeView = existing == null ? null : menuSnapshot(existing);
        MenuEntry entry = menuConfigRepository.findByTarget(targetType, targetCode)
                .orElseGet(() -> {
                    MenuEntry created = new MenuEntry();
                    created.setTargetType(targetType);
                    created.setTargetCode(targetCode);
                    created.setCreateTime(now);
                    return created;
                });
        entry.setMenuName(request.getMenuName());
        entry.setIcon(request.getIcon());
        entry.setGroupCode(resolveGroupCode(targetType, request.getGroupCode()));
        entry.setSortOrder(request.getSortOrder() == null ? 0L : request.getSortOrder());
        entry.setEnabled(request.getEnabled() == null || request.getEnabled());
        entry.setUpdateTime(now);
        menuConfigRepository.save(entry);

        long newVersion = bumpGlobalVersion();
        cacheService.evict(CacheKeyBuilder.menuTree());
        invalidationPublisher.publish(SchemaInvalidationEvent.configChanged(
                CacheDomains.MENU_CONFIG, entry.targetKey(), newVersion));
        log.info("[menu-config] 挂载配置保存: targetKey={}, enabled={}, version={}",
                entry.targetKey(), entry.isEnabled(), newVersion);
        configAuditService.recordConfig("menu_config", targetType + ":" + targetCode,
                "mount", beforeView, menuSnapshot(entry));
        return entry;
    }

    // ==================== EC-05 已挂载视图 ====================

    /**
     * 已挂载菜单视图（缓存回源：L2 → L1 → 重建锁 → 回源）。
     *
     * @return 分组视图（含全局 schema_version）
     */
    public MenuMountedView mounted() {
        String key = CacheKeyBuilder.menuTree();
        String cached = cacheService.get(key);
        if (cached != null) {
            return resolveCached(cached);
        }
        String lockKey = CacheKeyBuilder.rebuildLock(CacheDomains.MENU_CONFIG, CacheKeyBuilder.MENU_TREE_CODE);
        String lockToken = cacheService.tryRebuildLock(lockKey);
        boolean locked = lockToken != null;
        try {
            if (locked) {
                long rebuildStart = System.nanoTime();
                MenuMountedView view = buildMountedView();
                // 回填前比对失效时间戳（评审修复 Minor-10），防旧值覆盖新值
                cacheService.putIfNotEvicted(key, toJson(view), rebuildStart);
                return view;
            }
            // 未获锁：50ms 轮询重读，超限直读不回填
            for (int i = 0; i < cacheService.rebuildMaxWaits(); i++) {
                sleep(cacheService.rebuildWaitMillis());
                cached = cacheService.get(key);
                if (cached != null) {
                    return resolveCached(cached);
                }
            }
            return buildMountedView();
        } finally {
            if (locked) {
                cacheService.releaseRebuildLock(lockKey, lockToken);
            }
        }
    }

    /** 启动预热：已挂载视图写入两级缓存（容错由 CacheWarmupRunner 承担） */
    public void warmup() {
        MenuMountedView view = buildMountedView();
        cacheService.put(CacheKeyBuilder.menuTree(), toJson(view));
        log.info("[menu-config] 预热完成: items={}, schemaVersion={}",
                view.getItems().size(), view.getSchemaVersion());
    }

    // ==================== 内部工具 ====================

    /** 组装 mounted 视图：enabled 过滤 → 已发布过滤 → 分组排序 */
    private MenuMountedView buildMountedView() {
        List<MenuEntry> enabledEntries = menuConfigRepository.findAll().stream()
                .filter(MenuEntry::isEnabled)
                .toList();
        Map<String, List<MenuMountedView.MenuItem>> grouped = new LinkedHashMap<>();
        for (MenuEntry entry : enabledEntries) {
            if ("entity".equals(entry.getTargetType())) {
                boolean published = schemaRepository.findByCode(entry.getTargetCode())
                        .map(schema -> schema.getStatus() == EntityStatus.PUBLISHED)
                        .orElse(false);
                if (!published) {
                    continue;
                }
            } else {
                // doc 条目：doc_schema 定义已发布（status=1）即出列，与 entity 同构
                boolean docPublished = docSchemaRepository.findByCode(entry.getTargetCode())
                        .map(DocSchemaDefinition::isPublished)
                        .orElse(false);
                if (!docPublished) {
                    continue;
                }
            }
            grouped.computeIfAbsent(entry.getGroupCode(), k -> new ArrayList<>())
                    .add(toItem(entry));
        }
        MenuMountedView view = new MenuMountedView();
        view.setSchemaVersion(menuConfigRepository.getSchemaVersion());
        // 固定分组顺序 + 未知组追加末尾
        List<MenuMountedView.MenuItem> items = new ArrayList<>();
        grouped.values().forEach(items::addAll);
        items.sort(Comparator
                .comparingInt((MenuMountedView.MenuItem item) -> {
                    int idx = GROUP_ORDER.indexOf(item.getGroupCode());
                    return idx < 0 ? GROUP_ORDER.size() : idx;
                })
                .thenComparingLong(MenuMountedView.MenuItem::getSortOrder));
        view.setItems(items);
        return view;
    }

    private static MenuMountedView.MenuItem toItem(MenuEntry entry) {
        MenuMountedView.MenuItem item = new MenuMountedView.MenuItem();
        item.setCode(entry.getTargetCode());
        item.setMenuName(entry.getMenuName());
        item.setIcon(entry.getIcon());
        item.setObjType(entry.getTargetType());
        item.setGroupCode(entry.getGroupCode());
        item.setSortOrder(entry.getSortOrder());
        return item;
    }

    /** 分组缺省：entity→basic_data、doc→biz_doc */
    /** 审计快照（RD-12 before/after，design/03 §2.10 JSON Diff） */
    private static Map<String, Object> menuSnapshot(MenuEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("target_type", entry.getTargetType());
        view.put("target_code", entry.getTargetCode());
        view.put("menu_name", entry.getMenuName());
        view.put("group_code", entry.getGroupCode());
        view.put("sort_order", entry.getSortOrder());
        view.put("enabled", entry.isEnabled());
        return view;
    }

    private static String resolveGroupCode(String targetType, String groupCode) {
        if (groupCode != null && !groupCode.isBlank()) {
            // 分组码固定值域 basic_data/biz_doc/ops（prd/12 §1.11，不支持自定义新增）
            if (!GROUP_ORDER.contains(groupCode)) {
                throw new BizException(ErrorCode.TYPE_INVALID, "非法菜单分组码: " + groupCode);
            }
            return groupCode;
        }
        return "doc".equals(targetType) ? "biz_doc" : "basic_data";
    }

    private long bumpGlobalVersion() {
        try {
            return menuConfigRepository.incrementSchemaVersion();
        } catch (RuntimeException e) {
            log.warn("[menu-config] 全局 schema_version 自增失败（不阻断业务）: {}", e.getMessage());
            return menuConfigRepository.getSchemaVersion();
        }
    }

    private MenuMountedView resolveCached(String cached) {
        if (CacheService.NULL_PLACEHOLDER.equals(cached)) {
            return new MenuMountedView();
        }
        try {
            return objectMapper.readValue(cached, MenuMountedView.class);
        } catch (JsonProcessingException e) {
            log.warn("[menu-config] 缓存反序列化失败，直读回源: {}", e.getMessage());
            return buildMountedView();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "菜单视图序列化失败: " + e.getMessage());
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
