package com.example.common.constants;

/**
 * MQ Topic / Group / Tag 常量收口（H-07 禁硬编码；design/07 §2.1~2.4）。
 *
 * <p>命名规范：Topic 小写连字符；集群消费 Group {@code {service}-cg-{业务}}；
 * 广播消费 Group {@code {service}-cg-{业务}-broadcast}；Tag 为业务对象维度
 * （doc_code / entity_code / cache_domain），与消息体内 event_type 正交。</p>
 */
public final class DocMqConstants {

    private DocMqConstants() {
    }

    /** Topic 规划（design/07 §2.2） */
    public static final class Topics {

        /** 单据状态变更事件（FIFO 顺序，Sharding Key = doc_uuid，Tag = doc_code） */
        public static final String DOC_STATUS_EVENTS = "doc-status-events";

        /** 实体数据变更事件（普通消息，Tag = entity_code） */
        public static final String ENTITY_CHANGE_EVENTS = "entity-change-events";

        /** 配置缓存失效广播（BROADCASTING，Tag = cache_domain；仅限可容忍丢失的通知类） */
        public static final String SCHEMA_INVALIDATION = "schema-invalidation";

        /** Saga 冲正失败通知（可选，本期默认关闭；死信真相源是 saga_dead_letter 表） */
        public static final String SAGA_DEAD_LETTER_NOTIFY = "saga-dead-letter-notify";

        private Topics() {
        }
    }

    /** 消费 Group 规划（design/07 §2.3） */
    public static final class Groups {

        /** runtime-service：单据状态事件（集群 + 顺序消费，平台内下游联动） */
        public static final String RUNTIME_DOC_STATUS = "runtime-service-cg-doc-status";

        /** runtime-service：实体变更联动（集群，P1） */
        public static final String RUNTIME_ENTITY_CHANGE = "runtime-service-cg-entity-change";

        /** meta-service：配置缓存失效（广播） */
        public static final String META_SCHEMA_INVALIDATION_BROADCAST =
                "meta-service-cg-schema-invalidation-broadcast";

        /** runtime-service：配置缓存失效（广播） */
        public static final String RUNTIME_SCHEMA_INVALIDATION_BROADCAST =
                "runtime-service-cg-schema-invalidation-broadcast";

        /** runtime-service：Saga 死信联动通知（可选，本期默认关闭） */
        public static final String RUNTIME_SAGA_DL_NOTIFY = "runtime-service-cg-saga-dl-notify";

        private Groups() {
        }
    }

    /** Tag 约定（design/07 §2.4：Broker 端过滤，禁止运行期拼接动态 Tag） */
    public static final class Tags {

        /** 全订阅 */
        public static final String ALL = "*";

        private Tags() {
        }
    }

    /** 事件类型字典（prd/12 §1.10） */
    public static final class EventTypes {

        /** 单据状态变更（主事件，EV-01） */
        public static final String DOC_STATUS_CHANGED = "DOC_STATUS_CHANGED";

        /** 单据创建 */
        public static final String DOC_CREATED = "DOC_CREATED";

        /** 实体数据变更（payload 含 change_kind: create/update/delete） */
        public static final String ENTITY_CHANGED = "ENTITY_CHANGED";

        /** 定义发布（缓存失效广播） */
        public static final String SCHEMA_PUBLISHED = "SCHEMA_PUBLISHED";

        /** 定义停用（缓存失效广播） */
        public static final String SCHEMA_DISABLED = "SCHEMA_DISABLED";

        /** 配置变更（状态机/规则绑定/事务配置/菜单/规则库保存生效，缓存失效广播） */
        public static final String CONFIG_CHANGED = "CONFIG_CHANGED";

        private EventTypes() {
        }
    }
}
