package com.example.common.constants;

/**
 * 缓存域字典（design/07 §2.4：广播 Tag 为 cache_domain，七类配置对象）。
 *
 * <p>阶段二自 meta-service 下沉至 doc-common：schema-invalidation 广播的
 * 生产端（meta-service）与消费端（meta/runtime）共用同一域字典。</p>
 */
public final class CacheDomains {

    /** 实体定义 */
    public static final String ENTITY_SCHEMA = "entity_schema";

    /** 单据定义 */
    public static final String DOC_SCHEMA = "doc_schema";

    /** 状态机配置 */
    public static final String STATE_MACHINE = "state_machine";

    /** 规则库 */
    public static final String RULE_LIB = "rule_lib";

    /** 单据规则绑定 */
    public static final String DOC_RULE_CONFIG = "doc_rule_config";

    /** 事务步骤配置 */
    public static final String TX_CONFIG = "tx_config";

    /** 菜单挂载 */
    public static final String MENU_CONFIG = "menu_config";

    private CacheDomains() {
    }
}
