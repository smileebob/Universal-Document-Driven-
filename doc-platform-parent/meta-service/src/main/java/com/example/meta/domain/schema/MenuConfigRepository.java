package com.example.meta.domain.schema;

import java.util.List;
import java.util.Optional;

/**
 * 菜单挂载仓储端口（domain 层，实现位于 infrastructure/repository）。
 *
 * <p>menu_config 无多元索引，全量聚合走 GetRange（配置表单键模式，行数百级）。
 * 全局 schema_version 计数行（target_key=sys_schema_version）也落本表，
 * 由实现侧过滤，不经 {@link #findAll()} 外溢。</p>
 */
public interface MenuConfigRepository {

    /**
     * 保存挂载条目（整行覆盖写；新增/更新共用）。
     *
     * @param entry 条目
     */
    void save(MenuEntry entry);

    /**
     * 按目标点查。
     *
     * @param targetType 目标类型（entity/doc）
     * @param targetCode 目标编码
     * @return 条目（未挂载 empty）
     */
    Optional<MenuEntry> findByTarget(String targetType, String targetCode);

    /**
     * 全量加载挂载条目（GetRange；不含 sys_schema_version 计数行）。
     *
     * @return 全部条目（含 enabled=0 的历史挂载，过滤由调用方决定）
     */
    List<MenuEntry> findAll();

    /**
     * 读取全局 schema_version（任一 schema/menu 变更即自增的版本探针，
     * design/10 §6.2 ②；无计数行时返回 0）。
     *
     * @return 全局版本号
     */
    long getSchemaVersion();

    /**
     * 全局 schema_version CAS 自增（计数行不存在则建行为 1）。
     *
     * @return 自增后的新版本号
     */
    long incrementSchemaVersion();
}
