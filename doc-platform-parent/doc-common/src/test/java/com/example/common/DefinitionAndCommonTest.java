package com.example.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreDefinitionLoader;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.PartitionMode;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.common.util.IdempotencyKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1 定义文件加载 + 公共件（ErrorCode/BizException/IdempotencyKey/PartitionKeys）验收测试。
 */
class DefinitionAndCommonTest {

    // ==================== V1 定义文件 ====================

    @Test
    @DisplayName("V1__base-tables.json：16 张表、TTL、hash 位数、索引与 Nested 预留点")
    void v1Definitions() {
        List<TableDefinition> tables = new TablestoreDefinitionLoader().loadAll();
        assertThat(tables).hasSize(16);

        // audit_log：hash6 + 无 TTL（原 400 天，因 OTS「TTL 索引要求基表禁更新」与多元索引不可兼得，改清理/归档任务兜底）
        TableDefinition auditLog = byName(tables, "audit_log");
        assertThat(auditLog.getPartitionMode()).isEqualTo(PartitionMode.HASH);
        assertThat(auditLog.getHashLength()).isEqualTo(6);
        assertThat(auditLog.getTtlSeconds()).isEqualTo(TableDefinition.TTL_FOREVER);
        assertThat(auditLog.getSearchIndexes()).extracting("name").containsExactly("audit_log_index");

        // event_outbox：hash4 + 无 TTL（原 30 天，同上：send_status/retry_count 需行更新，TTL 不可行）
        TableDefinition outbox = byName(tables, "event_outbox");
        assertThat(outbox.getHashLength()).isEqualTo(4);
        assertThat(outbox.getTtlSeconds()).isEqualTo(TableDefinition.TTL_FOREVER);
        assertThat(outbox.getSearchIndexes()).extracting("name").containsExactly("event_outbox_index");

        // idempotent_record：hash4 + TTL 24h
        assertThat(byName(tables, "idempotent_record").getTtlSeconds()).isEqualTo(86400L);

        // 配置表 PLAIN 模式
        assertThat(byName(tables, "entity_schema").getPartitionMode()).isEqualTo(PartitionMode.PLAIN);
        assertThat(byName(tables, "menu_config").partitionKeyName()).isEqualTo("target_key");

        // doc_data_history 与 doc_data 同构主键（partition_key + uuid）+ seq
        TableDefinition history = byName(tables, "doc_data_history");
        assertThat(history.getPrimaryKey()).extracting("name")
                .containsExactly("partition_key", "uuid", "seq");

        // attributes Nested 动态注册预留点（V1 subFields 为空）
        TableDefinition docData = byName(tables, "doc_data");
        IndexFieldDef attributes = docData.getSearchIndexes().get(0).getFields().stream()
                .filter(field -> "attributes".equals(field.getName()))
                .findFirst().orElseThrow();
        assertThat(attributes.getType()).isEqualTo(IndexFieldDef.TYPE_NESTED);
        assertThat(attributes.getSubFields()).isEmpty();

        // doc_data_index 基线含 deleted 逻辑删列（runtime QueryService 强制注入
        // term(deleted=0)，缺列会被真实 OTS 以 field_name[deleted] is not existed 拒绝）
        IndexFieldDef deleted = docData.getSearchIndexes().get(0).getFields().stream()
                .filter(field -> "deleted".equals(field.getName()))
                .findFirst().orElseThrow();
        assertThat(deleted.getType()).isEqualTo(IndexFieldDef.TYPE_LONG);

        // relations Nested 容器 isArray 标记从定义文件正确绑定（防 Jackson
        // boolean getter 推断为 array 导致 "isArray" 键静默丢失）
        IndexFieldDef relations = docData.getSearchIndexes().get(0).getFields().stream()
                .filter(field -> "relations".equals(field.getName()))
                .findFirst().orElseThrow();
        assertThat(relations.isArray()).isTrue();

        // saga 表与 unique_index
        assertThat(byName(tables, "saga_execution").getPrimaryKey()).extracting("name")
                .containsExactly("partition_key", "saga_id");
        assertThat(byName(tables, "saga_dead_letter").getSearchIndexes())
                .extracting("name").containsExactly("saga_dead_letter_index");
        assertThat(byName(tables, "unique_index").getPrimaryKey()).extracting("name")
                .containsExactly("partition_key", "unique_value");
    }

    private TableDefinition byName(List<TableDefinition> tables, String name) {
        return tables.stream().filter(table -> name.equals(table.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少表定义: " + name));
    }

    @Test
    @DisplayName("评审修复 Minor-14：V 序号按数值排序（V2 在 V10 前）")
    void versionNumericOrdering() {
        TablestoreDefinitionLoader loader = new TablestoreDefinitionLoader(resolverOf(
                jsonResource("V10__later.json", fileJson(10, "table_v10")),
                jsonResource("V2__mid.json", fileJson(2, "table_v2")),
                jsonResource("V1__base.json", fileJson(1, "table_v1"))));
        assertThat(loader.load("ignored"))
                .extracting(TableDefinition::getName)
                .containsExactly("table_v1", "table_v2", "table_v10");
    }

    @Test
    @DisplayName("评审修复 Minor-14：跨文件重名表 fail-fast")
    void duplicateTableFailFast() {
        TablestoreDefinitionLoader loader = new TablestoreDefinitionLoader(resolverOf(
                jsonResource("V1__base.json", fileJson(1, "same_table")),
                jsonResource("V2__dup.json", fileJson(2, "same_table"))));
        assertThatThrownBy(() -> loader.load("ignored"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same_table");
    }

    private static String fileJson(int version, String tableName) {
        return "{\"version\":" + version + ",\"tables\":[{\"name\":\"" + tableName
                + "\",\"primaryKey\":[{\"name\":\"partition_key\",\"type\":\"STRING\"}]}]}";
    }

    private static Resource jsonResource(String filename, String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static ResourcePatternResolver resolverOf(Resource... resources) {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) {
                return resources;
            }

            @Override
            public Resource getResource(String location) {
                return resources[0];
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

    // ==================== ErrorCode / BizException ====================

    @Test
    @DisplayName("ErrorCode：17 项错误码 + HTTP 映射（40001~40011/40401/40901/42201/42202/42901/50000）")
    void errorCode_mapping() {
        assertThat(ErrorCode.values()).hasSize(18);
        assertThat(ErrorCode.fromCode(40001).getHttpStatus()).isEqualTo(400);
        assertThat(ErrorCode.fromCode(40005).getHttpStatus()).isEqualTo(400);
        assertThat(ErrorCode.fromCode(40011).getHttpStatus()).isEqualTo(400);
        assertThat(ErrorCode.fromCode(40401).getHttpStatus()).isEqualTo(404);
        assertThat(ErrorCode.fromCode(40901).getHttpStatus()).isEqualTo(409);
        assertThat(ErrorCode.fromCode(42201).getHttpStatus()).isEqualTo(422);
        assertThat(ErrorCode.fromCode(42202).getHttpStatus()).isEqualTo(422);
        assertThat(ErrorCode.fromCode(42901).getHttpStatus()).isEqualTo(429);
        assertThat(ErrorCode.fromCode(50000).getHttpStatus()).isEqualTo(500);
        assertThatThrownBy(() -> ErrorCode.fromCode(99999))
                .isInstanceOf(IllegalArgumentException.class);

        BizException biz = new BizException(ErrorCode.RULE_FAILED, "规则校验未通过",
                java.util.Map.of("failed_rules", List.of("R1")));
        assertThat(biz.getCode()).isEqualTo(42201);
        assertThat(biz.getHttpStatus()).isEqualTo(422);
        assertThat(biz.getData()).isNotNull();
    }

    // ==================== IdempotencyKey ====================

    @Test
    @DisplayName("IdempotencyKey：UUID 生成与校验、非法键 40002")
    void idempotencyKey() {
        String key = IdempotencyKey.generate();
        assertThat(IdempotencyKey.isValidUuid(key)).isTrue();
        assertThat(IdempotencyKey.isValid(key)).isTrue();
        assertThat(IdempotencyKey.requireValid(key)).isEqualTo(key);

        assertThat(IdempotencyKey.isValidUuid("not-a-uuid")).isFalse();
        assertThat(IdempotencyKey.isValid(null)).isFalse();
        assertThat(IdempotencyKey.isValid("x".repeat(129))).isFalse();
        assertThat(IdempotencyKey.isValid("bad key with space")).isFalse();
        assertThatThrownBy(() -> IdempotencyKey.requireValid("bad key"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(40002);
    }

    // ==================== PartitionKeys ====================

    @Test
    @DisplayName("PartitionKeys：hashN 确定性、各表分区键格式")
    void partitionKeys() {
        assertThat(PartitionKeys.hash("abc", 4)).hasSize(4).matches("[0-9a-f]{4}");
        assertThat(PartitionKeys.hash("abc", 4)).isEqualTo(PartitionKeys.hash("abc", 4));

        String dataKey = PartitionKeys.dataKey("sales_order", "uuid-001");
        assertThat(dataKey).matches("sales_order_[0-9a-f]{4}");

        assertThat(PartitionKeys.logKey("log-001")).matches("log_[0-9a-f]{6}");
        assertThat(PartitionKeys.sagaKey("saga-001")).matches("saga_[0-9a-f]{4}");
        assertThat(PartitionKeys.idemKey("key-001")).matches("idem_[0-9a-f]{4}");
        assertThat(PartitionKeys.ruleKey("rule-001")).matches("rule_[0-9a-f]{4}");
        assertThat(PartitionKeys.uniqueIndexKey("customer", "customer_name", "华为"))
                .matches("customer_customer_name_[0-9a-f]{4}");

        assertThatThrownBy(() -> PartitionKeys.hash("", 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartitionKeys.hash("v", 33))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== MQ 常量 ====================

    @Test
    @DisplayName("DocMqConstants：Topic/Group 命名收口")
    void mqConstants() {
        assertThat(com.example.common.constants.DocMqConstants.Topics.DOC_STATUS_EVENTS)
                .isEqualTo("doc-status-events");
        assertThat(com.example.common.constants.DocMqConstants.Topics.SCHEMA_INVALIDATION)
                .isEqualTo("schema-invalidation");
        assertThat(com.example.common.constants.DocMqConstants.Groups.META_SCHEMA_INVALIDATION_BROADCAST)
                .endsWith("-broadcast");
        assertThat(Optional.of(com.example.common.constants.DocMqConstants.Tags.ALL)).contains("*");
    }
}
