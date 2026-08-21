package com.example.meta.infrastructure.repository;

/**
 * meta-service 表名/列名常量收口（H-07 禁魔法值；design/03 表结构）。
 */
public final class MetaTableNames {

    private MetaTableNames() {
    }

    // ==================== 表名 ====================

    /** 实体定义表（EC-01~04，单键模式） */
    public static final String ENTITY_SCHEMA = "entity_schema";

    /** 菜单挂载表（EC-05，单键模式 + 全局版本计数行） */
    public static final String MENU_CONFIG = "menu_config";

    /** 实体数据表（多元索引动态子字段注册目标，design/03 §4.3） */
    public static final String ENTITY_DATA = "entity_data";

    /** entity_data 多元索引名（V1 基线定义） */
    public static final String ENTITY_DATA_INDEX = "entity_data_index";

    /** 单据数据表（doc_data_index 动态子字段注册目标，design/03 §4.3） */
    public static final String DOC_DATA = "doc_data";

    /** doc_data 多元索引名（V1 基线定义） */
    public static final String DOC_DATA_INDEX = "doc_data_index";

    // ==================== entity_schema 主键/列 ====================

    public static final String PK_ENTITY_CODE = "entity_code";
    public static final String COL_ENTITY_NAME = "entity_name";
    public static final String COL_ENTITY_TYPE = "entity_type";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_ATTRIBUTES = "attributes";
    public static final String COL_STATUS = "status";
    public static final String COL_CREATOR = "creator";
    public static final String COL_CREATE_DEPT = "create_dept";
    public static final String COL_CREATE_TIME = "create_time";
    public static final String COL_UPDATE_TIME = "update_time";
    public static final String COL_VERSION = "version";
    public static final String COL_PUBLISHED_AT = "published_at";

    // ==================== menu_config 主键/列 ====================

    public static final String PK_TARGET_KEY = "target_key";
    public static final String COL_TARGET_TYPE = "target_type";
    public static final String COL_TARGET_CODE = "target_code";
    public static final String COL_MENU_NAME = "menu_name";
    public static final String COL_ICON = "icon";
    public static final String COL_GROUP_CODE = "group_code";
    public static final String COL_SORT_ORDER = "sort_order";
    public static final String COL_ENABLED = "enabled";

    // ==================== 阶段二：单据域表名/列名 ====================

    /** 单据定义表（DC-01~05，单键模式） */
    public static final String DOC_SCHEMA = "doc_schema";

    /** 单据状态机表（SM-01/02，单键模式，保存即生效） */
    public static final String STATE_MACHINE = "state_machine";

    /** 单据规则绑定表（RE-05，单键模式，保存即生效） */
    public static final String DOC_RULE_CONFIG = "doc_rule_config";

    /** 规则库表（RE-01/02，HASH4 分区） */
    public static final String RULE_LIB = "rule_lib";

    // doc_schema 主键/列
    public static final String PK_DOC_CODE = "doc_code";
    public static final String COL_DOC_UUID = "uuid";
    public static final String COL_DOC_NAME = "doc_name";
    public static final String COL_DOC_TYPE = "doc_type";
    public static final String COL_STATUS_DEFS = "status_defs";

    // state_machine / doc_rule_config 列
    public static final String COL_STATES = "states";
    public static final String COL_TRANSITIONS = "transitions";
    public static final String COL_CREATE_RULES = "create_rules";
    public static final String COL_STATUS_RULES = "status_rules";

    // rule_lib 主键/列
    public static final String PK_PARTITION_KEY = "partition_key";
    public static final String PK_RULE_UUID = "rule_uuid";
    public static final String COL_RULE_NAME = "rule_name";
    public static final String COL_RULE_TYPE = "rule_type";
    public static final String COL_EXPRESSION = "expression";
    public static final String COL_INTERFACE_CODE = "interface_code";

    // ==================== 阶段三：事务步骤配置 ====================

    /** 单据事务步骤配置表（TX-01，单键模式，保存即生效） */
    public static final String TX_CONFIG = "tx_config";

    /** tx_config 事务步骤清单 JSON 列（design/03 §2.8） */
    public static final String COL_TX_STEPS = "tx_steps";

    /** 全局 schema_version 计数行主键（复用 menu_config 表，无专表的过渡方案） */
    public static final String SYS_SCHEMA_VERSION_KEY = "sys_schema_version";

    /** 计数行版本列 */
    public static final String COL_SCHEMA_VERSION = "schema_version";
}
