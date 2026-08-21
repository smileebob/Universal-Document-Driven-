package com.example.meta.infrastructure.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EntitySchemaRepositoryImpl 仓储测试（InMemoryTablestore 仿真层）。
 */
class EntitySchemaRepositoryImplTest {

    private InMemoryTablestore template;
    private EntitySchemaRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        repository = new EntitySchemaRepositoryImpl(template, new ObjectMapper());
    }

    private EntitySchema draft(String code) {
        EntitySchema schema = new EntitySchema();
        schema.setCode(code);
        schema.setName(code + "-name");
        schema.setEntityType("base");
        schema.setStatus(EntityStatus.DRAFT);
        schema.setVersion(0L);
        schema.setCreateTime(1L);
        schema.setUpdateTime(1L);
        AttributeDef def = new AttributeDef();
        def.setCode("attr_a");
        def.setName("属性A");
        def.setDataType("string");
        schema.setAttributes(List.of(def));
        return schema;
    }

    @Test
    @DisplayName("insert + findByCode 往返一致（attributes JSON 序列化/反序列化）")
    void insert_and_findByCode_roundTrip() {
        repository.insert(draft("customer"));
        EntitySchema loaded = repository.findByCode("customer").orElseThrow();
        assertThat(loaded.getName()).isEqualTo("customer-name");
        assertThat(loaded.getStatus()).isEqualTo(EntityStatus.DRAFT);
        assertThat(loaded.getAttributes()).hasSize(1);
        assertThat(loaded.getAttributes().get(0).getCode()).isEqualTo("attr_a");
        assertThat(repository.findByCode("not_exist")).isEmpty();
    }

    @Test
    @DisplayName("重复编码 insert 抛 TsConditionCheckException（EXPECT_NOT_EXIST）")
    void insert_duplicate_conflict() {
        repository.insert(draft("customer"));
        assertThatThrownBy(() -> repository.insert(draft("customer")))
                .isInstanceOf(TsConditionCheckException.class);
    }

    @Test
    @DisplayName("updateWithVersion CAS：成功 +1，过期 version 冲突")
    void updateWithVersion_cas() {
        repository.insert(draft("customer"));
        EntitySchema schema = repository.findByCode("customer").orElseThrow();
        schema.setName("updated");
        schema.setStatus(EntityStatus.PUBLISHED);
        schema.setPublishedAt(999L);
        repository.updateWithVersion(schema);

        EntitySchema reloaded = repository.findByCode("customer").orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("updated");
        assertThat(reloaded.getStatus()).isEqualTo(EntityStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isEqualTo(999L);
        assertThat(reloaded.getVersion()).isEqualTo(1L);

        // 过期 version 再次更新 → 冲突
        assertThatThrownBy(() -> repository.updateWithVersion(schema))
                .isInstanceOf(TsConditionCheckException.class);
    }

    @Test
    @DisplayName("updateWithVersion：publishedAt 为 null 时删除该列")
    void updateWithVersion_deletePublishedAt() {
        repository.insert(draft("customer"));
        EntitySchema schema = repository.findByCode("customer").orElseThrow();
        schema.setStatus(EntityStatus.PUBLISHED);
        schema.setPublishedAt(100L);
        repository.updateWithVersion(schema);
        schema = repository.findByCode("customer").orElseThrow();
        schema.setPublishedAt(null);
        repository.updateWithVersion(schema);
        assertThat(repository.findByCode("customer").orElseThrow().getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("deleteByCode 与 findAll 全量")
    void delete_and_findAll() {
        repository.insert(draft("b_entity"));
        repository.insert(draft("a_entity"));
        assertThat(repository.findAll()).hasSize(2);
        repository.deleteByCode("a_entity");
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findByCode("a_entity")).isEmpty();
    }

    @Test
    @DisplayName("attributes JSON 损坏时返回空属性列表（不抛异常）")
    void corrupted_attributes_returnsEmpty() {
        template.putRow(MetaTableNames.ENTITY_SCHEMA, new TsRow(
                TsPrimaryKey.of(MetaTableNames.PK_ENTITY_CODE, "broken"),
                java.util.Map.of(
                        MetaTableNames.COL_ENTITY_NAME, TsColumnValue.of("broken"),
                        MetaTableNames.COL_STATUS, TsColumnValue.of(100L),
                        MetaTableNames.COL_VERSION, TsColumnValue.of(0L),
                        MetaTableNames.COL_ATTRIBUTES, TsColumnValue.of("{invalid-json"))));
        assertThat(repository.findByCode("broken").orElseThrow().getAttributes()).isEmpty();
    }
}
