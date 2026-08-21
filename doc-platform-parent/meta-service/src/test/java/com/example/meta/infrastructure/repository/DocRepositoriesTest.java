package com.example.meta.infrastructure.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.domain.doc.DocRuleConfig;
import com.example.meta.domain.doc.DocSchemaDefinition;
import com.example.meta.domain.doc.RuleLibEntry;
import com.example.meta.domain.doc.StateMachineConfig;
import com.example.meta.service.doc.DocDtoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段二单据域四仓储实现测试（InMemoryTablestore 仿真层）：
 * doc_schema / state_machine / rule_lib / doc_rule_config 往返、CAS 与条件删除。
 */
class DocRepositoriesTest {

    private InMemoryTablestore template;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DocSchemaRepositoryImpl docSchemaRepository;
    private StateMachineRepositoryImpl stateMachineRepository;
    private RuleLibRepositoryImpl ruleLibRepository;
    private DocRuleConfigRepositoryImpl docRuleConfigRepository;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        docSchemaRepository = new DocSchemaRepositoryImpl(template, objectMapper);
        stateMachineRepository = new StateMachineRepositoryImpl(template, objectMapper);
        ruleLibRepository = new RuleLibRepositoryImpl(template);
        docRuleConfigRepository = new DocRuleConfigRepositoryImpl(template, objectMapper);
    }

    private DocSchemaDefinition draft(String code) {
        DocSchemaDefinition definition = new DocSchemaDefinition();
        definition.setUuid("uuid-" + code);
        definition.setCode(code);
        definition.setName(code + "-name");
        definition.setDocType("biz");
        definition.setDescription("desc");
        definition.setStatus(DocSchemaDefinition.STATUS_DRAFT);
        definition.setVersion(0L);
        definition.setAttributes(List.of(Map.of("code", "order_no", "name", "订单号")));
        definition.setStatusDefs(List.of(Map.of("status", 100L, "name", "暂存")));
        definition.setCreator("system");
        definition.setCreateTime(1L);
        definition.setUpdateTime(1L);
        return definition;
    }

    // ==================== DocSchemaRepositoryImpl ====================

    @Test
    @DisplayName("doc_schema：insert + findByCode 往返（attributes/status_defs JSON 列）")
    void docSchema_roundTrip() {
        docSchemaRepository.insert(draft("po"));
        DocSchemaDefinition loaded = docSchemaRepository.findByCode("po").orElseThrow();
        assertThat(loaded.getName()).isEqualTo("po-name");
        assertThat(loaded.getAttributes()).hasSize(1);
        assertThat(loaded.getStatusDefs()).hasSize(1);
        assertThat(docSchemaRepository.findByCode("not_exist")).isEmpty();
        assertThat(docSchemaRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("doc_schema：重复编码抛 TsConditionCheckException（EXPECT_NOT_EXIST）")
    void docSchema_duplicate() {
        docSchemaRepository.insert(draft("po"));
        assertThatThrownBy(() -> docSchemaRepository.insert(draft("po")))
                .isInstanceOf(TsConditionCheckException.class);
    }

    @Test
    @DisplayName("doc_schema：version CAS 更新成功自增；过期 version 抛冲突")
    void docSchema_updateWithVersion() {
        docSchemaRepository.insert(draft("po"));
        DocSchemaDefinition loaded = docSchemaRepository.findByCode("po").orElseThrow();
        loaded.setStatus(DocSchemaDefinition.STATUS_PUBLISHED);
        loaded.setPublishedAt(10L);
        loaded.setUpdateTime(10L);
        docSchemaRepository.updateWithVersion(loaded);
        DocSchemaDefinition after = docSchemaRepository.findByCode("po").orElseThrow();
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getStatus()).isEqualTo(DocSchemaDefinition.STATUS_PUBLISHED);
        assertThat(after.getPublishedAt()).isEqualTo(10L);

        loaded.setVersion(0L); // 过期快照
        assertThatThrownBy(() -> docSchemaRepository.updateWithVersion(loaded))
                .isInstanceOf(TsConditionCheckException.class);
    }

    @Test
    @DisplayName("doc_schema：条件删除（version 快照 CAS）；版本不符抛冲突")
    void docSchema_deleteIfDraft() {
        docSchemaRepository.insert(draft("po"));
        assertThatThrownBy(() -> docSchemaRepository.deleteByCodeIfDraft("po", 99L))
                .isInstanceOf(TsConditionCheckException.class);
        docSchemaRepository.deleteByCodeIfDraft("po", 0L);
        assertThat(docSchemaRepository.findByCode("po")).isEmpty();
    }

    @Test
    @DisplayName("doc_schema：attributes JSON 损坏降级空列表（不抛异常）")
    void docSchema_corruptJson_degrade() {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_DOC_UUID, TsColumnValue.of("uuid-bad"));
        columns.put(MetaTableNames.COL_DOC_NAME, TsColumnValue.of("坏数据"));
        columns.put(MetaTableNames.COL_ATTRIBUTES, TsColumnValue.of("{corrupt"));
        columns.put(MetaTableNames.COL_STATUS_DEFS, TsColumnValue.of(""));
        columns.put(MetaTableNames.COL_STATUS, TsColumnValue.of(100L));
        columns.put(MetaTableNames.COL_VERSION, TsColumnValue.of(0L));
        template.putRow(MetaTableNames.DOC_SCHEMA, new TsRow(
                TsPrimaryKey.of(MetaTableNames.PK_DOC_CODE, "bad"), columns));
        DocSchemaDefinition loaded = docSchemaRepository.findByCode("bad").orElseThrow();
        assertThat(loaded.getAttributes()).isEmpty();
        assertThat(loaded.getStatusDefs()).isEmpty();
    }

    // ==================== StateMachineRepositoryImpl ====================

    @Test
    @DisplayName("state_machine：insert/findByDocCode/updateWithVersion 往返")
    void stateMachine_roundTrip() {
        StateMachineConfig config = new StateMachineConfig();
        config.setDocCode("po");
        config.setVersion(0L);
        config.setStates(List.of(Map.of("status", 100L, "name", "暂存")));
        config.setTransitions(List.of(Map.of("start", 100L, "end", List.of(10L))));
        config.setUpdateTime(1L);

        stateMachineRepository.insert(config);
        StateMachineConfig loaded = stateMachineRepository.findByDocCode("po").orElseThrow();
        assertThat(loaded.getStates()).hasSize(1);
        assertThat(loaded.getTransitions()).hasSize(1);

        loaded.setTransitions(List.of());
        stateMachineRepository.updateWithVersion(loaded);
        assertThat(stateMachineRepository.findByDocCode("po").orElseThrow().getVersion())
                .isEqualTo(1L);
        assertThat(stateMachineRepository.findByDocCode("none")).isEmpty();

        assertThatThrownBy(() -> stateMachineRepository.insert(config))
                .isInstanceOf(TsConditionCheckException.class);
    }

    // ==================== RuleLibRepositoryImpl ====================

    @Test
    @DisplayName("rule_lib：insert/find/update/条件删除/findAll 往返")
    void ruleLib_roundTrip() {
        RuleLibEntry entry = new RuleLibEntry();
        entry.setUuid("r-uuid-1");
        entry.setRuleName("金额校验");
        entry.setRuleType("expression");
        entry.setExpression("#amount > 0");
        entry.setEnabled(true);
        entry.setVersion(0L);
        entry.setCreator("system");
        entry.setCreateTime(1L);
        entry.setUpdateTime(1L);

        ruleLibRepository.insert(entry);
        assertThatThrownBy(() -> ruleLibRepository.insert(entry))
                .isInstanceOf(TsConditionCheckException.class);
        RuleLibEntry loaded = ruleLibRepository.findByUuid("r-uuid-1").orElseThrow();
        assertThat(loaded.getRuleName()).isEqualTo("金额校验");
        assertThat(loaded.getExpression()).isEqualTo("#amount > 0");
        assertThat(ruleLibRepository.findAll()).hasSize(1);

        loaded.setExpression("#amount > 100");
        ruleLibRepository.updateWithVersion(loaded);
        assertThat(ruleLibRepository.findByUuid("r-uuid-1").orElseThrow().getVersion())
                .isEqualTo(1L);

        assertThatThrownBy(() -> ruleLibRepository.deleteByUuidIfVersion("r-uuid-1", 0L))
                .isInstanceOf(TsConditionCheckException.class);
        ruleLibRepository.deleteByUuidIfVersion("r-uuid-1", 1L);
        assertThat(ruleLibRepository.findByUuid("r-uuid-1")).isEmpty();

        // 删除后同 uuid 可再次创建（EXPECT_NOT_EXIST 语义）
        ruleLibRepository.insert(entry);
        assertThat(ruleLibRepository.findByUuid("r-uuid-1")).isPresent();
    }

    // ==================== DocRuleConfigRepositoryImpl ====================

    @Test
    @DisplayName("doc_rule_config：insert/find/update/findAll 往返")
    void docRuleConfig_roundTrip() {
        DocRuleConfig config = new DocRuleConfig();
        config.setDocCode("po");
        config.setVersion(0L);
        config.setCreateRules(List.of("r1"));
        config.setStatusRules(List.of(Map.of("status", 10L, "rules", List.of("r2"))));
        config.setUpdateTime(1L);

        docRuleConfigRepository.insert(config);
        DocRuleConfig loaded = docRuleConfigRepository.findByDocCode("po").orElseThrow();
        assertThat(loaded.getCreateRules()).containsExactly("r1");
        assertThat(loaded.getStatusRules()).hasSize(1);
        assertThat(docRuleConfigRepository.findAll()).hasSize(1);

        loaded.setCreateRules(List.of());
        docRuleConfigRepository.updateWithVersion(loaded);
        assertThat(docRuleConfigRepository.findByDocCode("po").orElseThrow().getVersion())
                .isEqualTo(1L);
        assertThat(docRuleConfigRepository.findByDocCode("none")).isEmpty();
    }

    // ==================== DocDtoMapper ====================

    @Test
    @DisplayName("DocDtoMapper：四类领域对象 → 内部契约 DTO 字段对齐")
    void dtoMapper() {
        DocSchemaDefinition definition = draft("po");
        definition.setStatus(DocSchemaDefinition.STATUS_PUBLISHED);
        definition.setVersion(2L);
        DocSchemaDTO schemaDto = DocDtoMapper.toDto(definition);
        assertThat(schemaDto.getCode()).isEqualTo("po");
        assertThat(schemaDto.getStatus()).isEqualTo(1);
        assertThat(schemaDto.getSchemaVersion()).isEqualTo(2);

        StateMachineConfig sm = new StateMachineConfig();
        sm.setDocCode("po");
        sm.setVersion(3L);
        sm.setStates(List.of(Map.of("status", 100L)));
        sm.setTransitions(List.of());
        StateMachineDTO smDto = DocDtoMapper.toDto(sm);
        assertThat(smDto.getDocCode()).isEqualTo("po");
        assertThat(smDto.getSchemaVersion()).isEqualTo(3L);

        DocRuleConfig rc = new DocRuleConfig();
        rc.setDocCode("po");
        rc.setVersion(1L);
        rc.setCreateRules(List.of("r1"));
        rc.setStatusRules(List.of());
        DocRuleConfigDTO rcDto = DocDtoMapper.toDto(rc);
        assertThat(rcDto.getCreateRules()).containsExactly("r1");

        RuleLibEntry rule = new RuleLibEntry();
        rule.setUuid("r1");
        rule.setRuleName("n");
        rule.setRuleType("expression");
        rule.setExpression("#x");
        rule.setEnabled(false);
        rule.setVersion(4L);
        RuleLibDTO ruleDto = DocDtoMapper.toDto(rule);
        assertThat(ruleDto.getUuid()).isEqualTo("r1");
        assertThat(ruleDto.getEnabled()).isFalse();
        assertThat(ruleDto.getSchemaVersion()).isEqualTo(4L);
    }
}
