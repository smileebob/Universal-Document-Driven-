package com.example.meta.service.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.meta.api.dto.AttributeDTO;
import com.example.meta.api.dto.AttributeType;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.meta.domain.schema.AttributeDef;
import com.example.meta.domain.schema.EntitySchema;
import com.example.meta.domain.schema.EntityStatus;

/**
 * domain ↔ 契约 DTO 映射（meta-service-api EC 契约固化口径）。
 *
 * <p>data_type → AttributeType：string→STRING；integer/decimal→NUMBER；
 * boolean→BOOLEAN；date/datetime→DATE；ref→REF；table（明细子表）本期
 * 不经契约外溢（遗留项登记）。status 数值 → SchemaStatus 枚举。</p>
 */
public final class SchemaDtoMapper {

    private SchemaDtoMapper() {
    }

    /**
     * domain 定义 → 契约 DTO。
     *
     * @param schema domain 定义
     * @return 契约 DTO
     */
    public static EntitySchemaDTO toDto(EntitySchema schema) {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode(schema.getCode());
        dto.setName(schema.getName());
        dto.setDescription(schema.getDescription());
        dto.setObjectType("entity");
        dto.setStatus(toContractStatus(schema.getStatus()));
        dto.setVersion(schema.getVersion());
        dto.setPublishedAt(schema.getPublishedAt());
        List<AttributeDTO> attributes = new ArrayList<>();
        int order = 0;
        for (AttributeDef def : schema.getAttributes()) {
            AttributeType type = mapAttributeType(def.getDataType());
            if (type == null) {
                // table 等不经契约外溢的类型跳过
                continue;
            }
            AttributeDTO attr = new AttributeDTO();
            attr.setCode(def.getCode());
            attr.setName(def.getName());
            attr.setType(type);
            attr.setRequired(def.isRequired());
            attr.setUnique(def.isUnique());
            attr.setRefEntityCode(def.getRefEntityCode());
            attr.setMaxLength(def.getMaxLength());
            attr.setPrecision(def.getPrecision());
            attr.setEditable(def.isEditable());
            attr.setDefaultValue(def.getDefaultValue());
            attr.setDisplayField(def.getDisplayField());
            attr.setSortOrder(def.getListOrder() != null ? def.getListOrder() : order);
            attributes.add(attr);
            order++;
        }
        dto.setAttributes(attributes);
        return dto;
    }

    /** domain 状态 → 契约状态枚举 */
    public static SchemaStatus toContractStatus(EntityStatus status) {
        switch (status) {
            case PUBLISHED:
                return SchemaStatus.PUBLISHED;
            case DISABLED:
                return SchemaStatus.DISABLED;
            case DRAFT:
            default:
                return SchemaStatus.DRAFT;
        }
    }

    /** data_type → 契约属性类型；table/未知类型返回 null（不外溢） */
    public static AttributeType mapAttributeType(String dataType) {
        if (dataType == null) {
            return null;
        }
        switch (dataType.toLowerCase(Locale.ROOT)) {
            case "string":
                return AttributeType.STRING;
            case "integer":
                return AttributeType.INTEGER;
            case "decimal":
                return AttributeType.DECIMAL;
            case "number":
                return AttributeType.NUMBER;
            case "boolean":
                return AttributeType.BOOLEAN;
            case "date":
            case "datetime":
                return AttributeType.DATE;
            case "ref":
                return AttributeType.REF;
            default:
                return null;
        }
    }
}
