package com.example.meta.service.schema;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.meta.api.dto.AttributeType;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntityStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaDtoMapper 契约映射测试。
 */
class SchemaDtoMapperTest {

    private AttributeDef def(String code, String dataType) {
        AttributeDef def = new AttributeDef();
        def.setCode(code);
        def.setName(code + "-name");
        def.setDataType(dataType);
        def.setListOrder(5);
        return def;
    }

    @Test
    @DisplayName("toDto：固定字段 objectType=entity 与属性映射（table 不外溢）")
    void toDto_mapping() {
        EntitySchema schema = new EntitySchema();
        schema.setCode("customer");
        schema.setName("客户");
        schema.setStatus(EntityStatus.PUBLISHED);
        schema.setVersion(3L);
        schema.setPublishedAt(100L);
        schema.setAttributes(List.of(
                def("a_string", "string"),
                def("a_int", "integer"),
                def("a_dec", "decimal"),
                def("a_bool", "boolean"),
                def("a_date", "date"),
                def("a_datetime", "datetime"),
                def("a_ref", "ref"),
                def("a_table", "table")));

        EntitySchemaDTO dto = SchemaDtoMapper.toDto(schema);

        assertThat(dto.getObjectType()).isEqualTo("entity");
        assertThat(dto.getCode()).isEqualTo("customer");
        assertThat(dto.getStatus()).isEqualTo(SchemaStatus.PUBLISHED);
        assertThat(dto.getVersion()).isEqualTo(3L);
        assertThat(dto.getPublishedAt()).isEqualTo(100L);
        // table 不经契约外溢 → 7 个属性
        assertThat(dto.getAttributes()).hasSize(7);
        assertThat(dto.getAttributes()).extracting("sortOrder").contains(5);
    }

    @Test
    @DisplayName("mapAttributeType 全分支")
    void mapAttributeType_branches() {
        assertThat(SchemaDtoMapper.mapAttributeType("string")).isEqualTo(AttributeType.STRING);
        assertThat(SchemaDtoMapper.mapAttributeType("integer")).isEqualTo(AttributeType.INTEGER);
        assertThat(SchemaDtoMapper.mapAttributeType("decimal")).isEqualTo(AttributeType.DECIMAL);
        assertThat(SchemaDtoMapper.mapAttributeType("number")).isEqualTo(AttributeType.NUMBER);
        assertThat(SchemaDtoMapper.mapAttributeType("boolean")).isEqualTo(AttributeType.BOOLEAN);
        assertThat(SchemaDtoMapper.mapAttributeType("date")).isEqualTo(AttributeType.DATE);
        assertThat(SchemaDtoMapper.mapAttributeType("datetime")).isEqualTo(AttributeType.DATE);
        assertThat(SchemaDtoMapper.mapAttributeType("ref")).isEqualTo(AttributeType.REF);
        assertThat(SchemaDtoMapper.mapAttributeType("table")).isNull();
        assertThat(SchemaDtoMapper.mapAttributeType(null)).isNull();
    }

    @Test
    @DisplayName("toContractStatus 三态映射")
    void toContractStatus_branches() {
        assertThat(SchemaDtoMapper.toContractStatus(EntityStatus.PUBLISHED))
                .isEqualTo(SchemaStatus.PUBLISHED);
        assertThat(SchemaDtoMapper.toContractStatus(EntityStatus.DISABLED))
                .isEqualTo(SchemaStatus.DISABLED);
        assertThat(SchemaDtoMapper.toContractStatus(EntityStatus.DRAFT))
                .isEqualTo(SchemaStatus.DRAFT);
    }
}
