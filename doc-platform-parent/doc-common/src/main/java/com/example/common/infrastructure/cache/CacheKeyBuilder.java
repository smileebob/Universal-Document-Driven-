package com.example.common.infrastructure.cache;

/**
 * 缓存 Key 构造器（design/06 §二 Key 清单，appcode=ddp）。
 *
 * <ul>
 *   <li>{@code ddp:meta:schema:entity:{code}} / {@code ddp:meta:schema:doc:{code}}</li>
 *   <li>{@code ddp:meta:sm:{code}} / {@code ddp:meta:rule:{uuid}} /
 *       {@code ddp:meta:docrule:{code}} / {@code ddp:meta:tx:{code}}</li>
 *   <li>{@code ddp:meta:menu:tree:v2}（v2：mounted 视图扁平结构契约对齐后升版）</li>
 *   <li>重建锁 {@code ddp:meta:lock:rebuild:{域}:{code}}</li>
 *   <li>运行侧流转分布式锁 {@code ddp:runtime:lock:doc:{uuid}}（design/05 §1.1）</li>
 * </ul>
 *
 * <p>阶段二自 meta-service 下沉至 doc-common：runtime 侧 schema 缓存与
 * meta 侧共用同一 Key 规范（共享 Redis Key 空间，失效广播跨服务对齐）。</p>
 */
public final class CacheKeyBuilder {

    /** 应用前缀 */
    public static final String APP_PREFIX = "ddp";

    private static final String META_PREFIX = APP_PREFIX + ":meta:";

    /** 菜单域重建锁固定 code（菜单为全局聚合键） */
    public static final String MENU_TREE_CODE = "tree";

    private CacheKeyBuilder() {
    }

    /** 实体定义缓存键 */
    public static String entitySchema(String code) {
        return META_PREFIX + "schema:entity:" + code;
    }

    /** 单据定义缓存键 */
    public static String docSchema(String code) {
        return META_PREFIX + "schema:doc:" + code;
    }

    /** 状态机缓存键 */
    public static String stateMachine(String code) {
        return META_PREFIX + "sm:" + code;
    }

    /** 规则库缓存键（rule_uuid 维度） */
    public static String rule(String uuid) {
        return META_PREFIX + "rule:" + uuid;
    }

    /** 单据规则绑定缓存键 */
    public static String docRuleConfig(String code) {
        return META_PREFIX + "docrule:" + code;
    }

    /** 事务步骤配置缓存键 */
    public static String txConfig(String code) {
        return META_PREFIX + "tx:" + code;
    }

    /** 菜单树缓存键（全局聚合，v2=扁平 items 结构） */
    public static String menuTree() {
        return META_PREFIX + "menu:tree:v2";
    }

    /**
     * 击穿重建锁键（SETNX，TTL 3s，design/06 §5.1）。
     *
     * @param domain 缓存域（CacheDomains 取值）
     * @param code   对象编码（menu_config 域取 {@link #MENU_TREE_CODE}）
     * @return 锁键
     */
    public static String rebuildLock(String domain, String code) {
        return META_PREFIX + "lock:rebuild:" + domain + ":" + code;
    }

    /**
     * 运行侧单据流转分布式锁键（design/05 §1.1：Redisson tryLock 3s，
     * 无 leaseTime 由看门狗续期；仅 runtime-service 使用，不写共享缓存空间）。
     *
     * @param uuid 单据实例 uuid
     * @return 锁键
     */
    public static String runtimeDocTransitionLock(String uuid) {
        return APP_PREFIX + ":runtime:lock:doc:" + uuid;
    }
}
