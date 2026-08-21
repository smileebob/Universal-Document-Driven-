package com.example.meta.infrastructure.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.meta.domain.schema.MenuConfigRepository;
import com.example.meta.domain.schema.MenuEntry;

/**
 * 菜单挂载仓储实现（menu_config 单键表，EC-05）。
 *
 * <p>全局 schema_version 计数行（target_key={@value MetaTableNames#SYS_SCHEMA_VERSION_KEY}）
 * 也落本表：无专表的过渡方案，CAS 自增（冲突退避重试），findAll 按 target_type
 * 列缺失自然过滤该。</p>
 */
@Component
public class MenuConfigRepositoryImpl implements MenuConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(MenuConfigRepositoryImpl.class);

    /** 计数行 CAS 竞争最大重试次数 */
    private static final int VERSION_CAS_MAX_RETRIES = 8;

    private final TablestoreTemplate template;

    public MenuConfigRepositoryImpl(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public void save(MenuEntry entry) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_TARGET_TYPE, TsColumnValue.of(entry.getTargetType()));
        columns.put(MetaTableNames.COL_TARGET_CODE, TsColumnValue.of(entry.getTargetCode()));
        columns.put(MetaTableNames.COL_MENU_NAME, TsColumnValue.of(entry.getMenuName()));
        if (entry.getIcon() != null) {
            columns.put(MetaTableNames.COL_ICON, TsColumnValue.of(entry.getIcon()));
        }
        columns.put(MetaTableNames.COL_GROUP_CODE, TsColumnValue.of(entry.getGroupCode()));
        columns.put(MetaTableNames.COL_SORT_ORDER, TsColumnValue.of(entry.getSortOrder()));
        columns.put(MetaTableNames.COL_ENABLED, TsColumnValue.of(entry.isEnabled() ? 1L : 0L));
        if (entry.getCreateTime() != null) {
            columns.put(MetaTableNames.COL_CREATE_TIME, TsColumnValue.of(entry.getCreateTime()));
        }
        if (entry.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(entry.getUpdateTime()));
        }
        template.putRow(MetaTableNames.MENU_CONFIG,
                new TsRow(pk(entry.targetKey()), columns));
    }

    @Override
    public Optional<MenuEntry> findByTarget(String targetType, String targetCode) {
        return template.getRow(MetaTableNames.MENU_CONFIG, pk(targetType + "_" + targetCode))
                .map(this::toEntry);
    }

    @Override
    public List<MenuEntry> findAll() {
        List<TsRow> rows = template.getRange(MetaTableNames.MENU_CONFIG);
        List<MenuEntry> entries = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            // sys_schema_version 计数行无 target_type 列，自然过滤
            if (!row.hasColumn(MetaTableNames.COL_TARGET_TYPE)) {
                continue;
            }
            entries.add(toEntry(row));
        }
        return entries;
    }

    @Override
    public long getSchemaVersion() {
        return template.getRow(MetaTableNames.MENU_CONFIG, sysPk())
                .map(row -> row.getLong(MetaTableNames.COL_SCHEMA_VERSION))
                .orElse(0L);
    }

    @Override
    public long incrementSchemaVersion() {
        for (int attempt = 0; attempt < VERSION_CAS_MAX_RETRIES; attempt++) {
            Optional<TsRow> current = template.getRow(MetaTableNames.MENU_CONFIG, sysPk());
            long now = System.currentTimeMillis();
            if (current.isEmpty()) {
                // 计数行不存在：幂等建行为 1
                Map<String, TsColumnValue> columns = new LinkedHashMap<>();
                columns.put(MetaTableNames.COL_SCHEMA_VERSION, TsColumnValue.of(1L));
                columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(now));
                try {
                    template.putRowIfAbsent(MetaTableNames.MENU_CONFIG,
                            new TsRow(sysPk(), columns));
                    return 1L;
                } catch (TsConditionCheckException e) {
                    continue; // 并发建行，退避重试
                }
            }
            long version = current.orElseThrow().getLong(MetaTableNames.COL_SCHEMA_VERSION) == null
                    ? 0L : current.orElseThrow().getLong(MetaTableNames.COL_SCHEMA_VERSION);
            try {
                template.updateRow(MetaTableNames.MENU_CONFIG, sysPk(),
                        List.of(ColumnUpdate.put(MetaTableNames.COL_SCHEMA_VERSION,
                                        TsColumnValue.of(version + 1)),
                                ColumnUpdate.put(MetaTableNames.COL_UPDATE_TIME,
                                        TsColumnValue.of(now))),
                        RowCondition.expectExistAndColumnEq(
                                MetaTableNames.COL_SCHEMA_VERSION, TsColumnValue.of(version)));
                return version + 1;
            } catch (TsConditionCheckException e) {
                log.debug("[menu-config] schema_version CAS 竞争，重试 {}/{}",
                        attempt + 1, VERSION_CAS_MAX_RETRIES);
            }
        }
        throw new BizException(ErrorCode.STATUS_CONFLICT, "全局 schema_version 自增冲突重试耗尽");
    }

    // ==================== 映射 ====================

    private static TsPrimaryKey pk(String targetKey) {
        return TsPrimaryKey.of(MetaTableNames.PK_TARGET_KEY, targetKey);
    }

    private static TsPrimaryKey sysPk() {
        return pk(MetaTableNames.SYS_SCHEMA_VERSION_KEY);
    }

    private MenuEntry toEntry(TsRow row) {
        MenuEntry entry = new MenuEntry();
        entry.setTargetType(row.getString(MetaTableNames.COL_TARGET_TYPE));
        entry.setTargetCode(row.getString(MetaTableNames.COL_TARGET_CODE));
        entry.setMenuName(row.getString(MetaTableNames.COL_MENU_NAME));
        entry.setIcon(row.getString(MetaTableNames.COL_ICON));
        entry.setGroupCode(row.getString(MetaTableNames.COL_GROUP_CODE));
        entry.setSortOrder(row.getLong(MetaTableNames.COL_SORT_ORDER) == null
                ? 0L : row.getLong(MetaTableNames.COL_SORT_ORDER));
        entry.setEnabled(row.getLong(MetaTableNames.COL_ENABLED) != null
                && row.getLong(MetaTableNames.COL_ENABLED) == 1L);
        entry.setCreateTime(row.getLong(MetaTableNames.COL_CREATE_TIME));
        entry.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
        return entry;
    }
}
