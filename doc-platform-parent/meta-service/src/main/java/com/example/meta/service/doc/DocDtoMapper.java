package com.example.meta.service.doc;

import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.doc.StateMachineConfig;

/**
 * 单据域领域模型 → 内部契约 DTO 映射（meta-service-api，snake_case 由 DTO 注解承担）。
 */
public final class DocDtoMapper {

    private DocDtoMapper() {
    }

    public static DocSchemaDTO toDto(DocSchemaDefinition definition) {
        DocSchemaDTO dto = new DocSchemaDTO();
        dto.setUuid(definition.getUuid());
        dto.setCode(definition.getCode());
        dto.setName(definition.getName());
        dto.setDocType(definition.getDocType());
        dto.setDescription(definition.getDescription());
        dto.setStatus((int) definition.getStatus());
        dto.setSchemaVersion((int) definition.getVersion());
        dto.setAttributes(definition.getAttributes());
        dto.setStatusDefs(definition.getStatusDefs());
        return dto;
    }

    public static StateMachineDTO toDto(StateMachineConfig config) {
        StateMachineDTO dto = new StateMachineDTO();
        dto.setDocCode(config.getDocCode());
        dto.setSchemaVersion(config.getVersion());
        dto.setStates(config.getStates());
        dto.setTransitions(config.getTransitions());
        return dto;
    }

    public static DocRuleConfigDTO toDto(DocRuleConfig config) {
        DocRuleConfigDTO dto = new DocRuleConfigDTO();
        dto.setDocCode(config.getDocCode());
        dto.setSchemaVersion(config.getVersion());
        dto.setCreateRules(config.getCreateRules());
        dto.setStatusRules(config.getStatusRules());
        return dto;
    }

    public static RuleLibDTO toDto(RuleLibEntry entry) {
        RuleLibDTO dto = new RuleLibDTO();
        dto.setUuid(entry.getUuid());
        dto.setRuleName(entry.getRuleName());
        dto.setRuleType(entry.getRuleType());
        dto.setExpression(entry.getExpression());
        dto.setInterfaceCode(entry.getInterfaceCode());
        dto.setEnabled(entry.isEnabled());
        dto.setDescription(entry.getDescription());
        dto.setSchemaVersion(entry.getVersion());
        return dto;
    }
}
