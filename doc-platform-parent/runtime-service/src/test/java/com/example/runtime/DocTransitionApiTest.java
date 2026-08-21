package com.example.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.rule.RuleExecutor;
import com.example.common.web.GlobalExceptionHandler;
import com.example.runtime.controller.DocDataController;
import com.example.runtime.controller.TransitionController;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.DocDefinition.DocAttribute;
import com.example.runtime.domain.model.DocDefinition.DocStateDef;
import com.example.runtime.domain.model.DocRuleBindings;
import com.example.runtime.domain.model.DocStateMachine;
import com.example.runtime.domain.model.RuleDefinition;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.runtime.domain.port.AuditLogStore;
import com.example.runtime.domain.port.DocConfigGateway;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.infrastructure.tablestore.TablestoreDocDataStore;
import com.example.runtime.infrastructure.tablestore.TablestoreIdempotencyStore;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.IdempotencyService;
import com.example.runtime.service.data.DocDataService;
import com.example.runtime.service.event.OutboxRelayService;
import com.example.runtime.service.rule.DocRuleChecker;
import com.example.runtime.service.saga.SagaOrchestrator;
import com.example.runtime.service.transition.TransitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段二单据引擎验收测试（八步链路 + RD-05~08，standalone MockMvc + InMemoryTablestore）。
 *
 * <p>用例编号对照 design/05 §四验收映射表：</p>
 * <ul>
 *   <li>AC-1（§四 1.4）同一单据并发流转 → 后到者 429 LOCK_BUSY；</li>
 *   <li>AC-2（§四 1.2③）锁内读新状态后非法流转 → 400 ILLEGAL_TRANSITION（40003）；</li>
 *   <li>AC-3（§四 3.1 F2/F5、3.5）模拟锁失效双写 → CAS 拦截 409，无重复历史行；</li>
 *   <li>AC-4（§四 5.2）高频重复点击 → 幂等承接回放，history 只增一条；</li>
 *   <li>AC-5（§四 C4/2.1）流转成功后详情立即可见新状态/version；</li>
 *   <li>规则失败 42201 + failed_rules 明细 + saga_executed=false；</li>
 *   <li>CRUD 状态约束：40008 状态不可编辑 / 40009 仅暂存可删（leader 补项③）；</li>
 *   <li>校验拒绝审计：operation=validation_reject（leader 补项④）。</li>
 * </ul>
 *
 * <p>阶段三适配：Saga/Outbox 中继在本测试中以 Mockito 桩注入（无 tx_config →
 * saga_triggered=false），outbox 断言含建单 DOC_CREATED 行。</p>
 */
class DocTransitionApiTest {

    private static final String DOC = "po";

    private InMemoryTablestore template;
    private ObjectMapper mapper;
    private TablestoreDocDataStore docDataStore;
    private RLock lock;
    private List<AuditEntry> audits;
    private RuleExecutor ruleExecutor;
    private MockMvc mockMvc;
    private FakeDocConfigGateway configGateway;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        configGateway = new FakeDocConfigGateway();
        configGateway.definition = poDefinition();
        configGateway.stateMachine = new DocStateMachine(DOC, Map.of(
                100L, Set.of(10L),
                10L, Set.of(20L, 100L),
                20L, Set.of(99L)));

        docDataStore = new TablestoreDocDataStore(template, mapper);
        audits = new ArrayList<>();
        AuditLogStore collectingStore = new AuditLogStore() {
            @Override
            public void append(AuditEntry entry) {
                audits.add(entry);
            }

            @Override
            public SearchResult search(QuerySpec spec) {
                throw new UnsupportedOperationException("test collecting store");
            }
        };
        AuditService auditService = new AuditService(collectingStore, Runnable::run);
        ruleExecutor = new RuleExecutor(2000, 1, 2, 16, 64);
        DocRuleChecker ruleChecker = new DocRuleChecker(configGateway, ruleExecutor);

        RedissonClient redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

        TransitionService transitionService = new TransitionService(
                redissonClient, configGateway, docDataStore, ruleChecker, auditService,
                mock(SagaOrchestrator.class), mock(OutboxRelayService.class));
        DocDataService docDataService = new DocDataService(
                configGateway, docDataStore, ruleChecker, auditService);
        IdempotencyService idempotencyService =
                new IdempotencyService(new TablestoreIdempotencyStore(template), mapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TransitionController(transitionService, idempotencyService),
                        new DocDataController(docDataService, idempotencyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        ruleExecutor.close();
    }

    // ==================== 主路径（AC-5：流转成功详情立即可见） ====================

    @Test
    @DisplayName("创建→提交流转成功：新状态/version/history_seq 立即可见（design/05 §四 C4/2.1）")
    void create_then_submit_detailImmediatelyVisible() throws Exception {
        String uuid = createPo(100);

        MvcResult result = mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice")
                        .content("{\"target_status\": 10, \"event\": \"submit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.from_status").value(100))
                .andExpect(jsonPath("$.data.to_status").value(10))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.history_seq").value(2))
                .andExpect(jsonPath("$.data.saga_triggered").value(false))
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("data").get("history_seq").asLong()).isEqualTo(2);

        // 流转成功后详情立即可见（C4）：同分区事务提交即强一致
        mockMvc.perform(get("/api/v1/data/doc/{doc}/{uuid}", DOC, uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_status").value(10))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status_name").value("已提交"));

        // T5 outbox 行（建单 DOC_CREATED + 流转 DOC_STATUS_CHANGED）与 ⑧ 同分区流转审计落库
        assertThat(template.getRange("event_outbox")).hasSize(2);
        assertThat(template.getRange("event_outbox"))
                .anyMatch(row -> "DOC_CREATED".equals(row.getString("event_type")))
                .anyMatch(row -> "DOC_STATUS_CHANGED".equals(row.getString("event_type")));
        // ⑧ 流转审计随局部事务写 audit_log 表（非 AuditService 旁路通道）
        assertThat(template.getRange("audit_log"))
                .anyMatch(row -> "transition".equals(row.getString("operation"))
                        && "po".equals(row.getString("target_code")));
    }

    // ==================== 状态历史读接口（RD-08 status_history，前端时间线契约） ====================

    @Test
    @DisplayName("状态历史：创建→提交后 seq 升序输出，字段名与表定义对齐")
    void statusHistory_seqAscending_fieldAligned() throws Exception {
        String uuid = createPo(100);
        mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice")
                        .content("{\"target_status\": 10, \"event\": \"submit\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/data/doc/{doc}/{uuid}/history", DOC, uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].seq").value(1))
                .andExpect(jsonPath("$.data[0].operation").value("create"))
                .andExpect(jsonPath("$.data[0].to_status").value(100))
                .andExpect(jsonPath("$.data[0].to_status_name").value("暂存"))
                .andExpect(jsonPath("$.data[0].operator").value("alice"))
                .andExpect(jsonPath("$.data[1].seq").value(2))
                .andExpect(jsonPath("$.data[1].from_status").value(100))
                .andExpect(jsonPath("$.data[1].to_status").value(10))
                .andExpect(jsonPath("$.data[1].to_status_name").value("已提交"))
                .andExpect(jsonPath("$.data[1].operation").value("submit"))
                .andExpect(jsonPath("$.data[1].version_after").value(2));

        // Store 层直读同口径：两条记录 seq 升序
        assertThat(docDataStore.findHistory(DOC, uuid)).hasSize(2);
    }

    @Test
    @DisplayName("状态历史：单据不存在 → 40401")
    void statusHistory_docNotFound_40401() throws Exception {
        mockMvc.perform(get("/api/v1/data/doc/{doc}/{uuid}/history", DOC, "ghost-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== AC-2：非法流转 40003 + validation_reject 审计 ====================

    @Test
    @DisplayName("非法流转 → 40003 + validation_reject 审计（design/05 §四 1.2③ / leader 补项④）")
    void illegalTransition_40003_withRejectAudit() throws Exception {
        String uuid = createPo(100);
        mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice")
                        .content("{\"target_status\": 20, \"event\": \"audit\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ILLEGAL_TRANSITION.getCode()));

        assertThat(audits).anyMatch(entry -> "validation_reject".equals(entry.getOperation())
                && entry.getAfterValue() != null && entry.getAfterValue().contains("\"code\":40003"));

        // 状态字典未登记的目标状态同样 40003
        mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_status\": 77}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ILLEGAL_TRANSITION.getCode()));
    }

    // ==================== AC-1：锁忙 42901 ====================

    @Test
    @DisplayName("并发流转锁忙：tryLock 3s 失败即 42901 不排队（design/05 §四 1.4）")
    void lockBusy_42901() throws Exception {
        String uuid = createPo(100);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_status\": 10}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.LOCK_BUSY.getCode()));
    }

    // ==================== 规则失败 42201 + failed_rules 明细 ====================

    @Test
    @DisplayName("规则校验失败 → 42201 + failed_rules 明细 + saga_executed=false（design/05 §四）")
    void ruleFailed_42201_withDetails() throws Exception {
        configGateway.rules.put("rule-deny", new RuleDefinition(
                "rule-deny", "拒绝提交", "expression", "false", null, true));
        configGateway.bindings = new DocRuleBindings(DOC, List.of(),
                Map.of(10L, List.of("rule-deny")));

        String uuid = createPo(100);
        mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice")
                        .content("{\"target_status\": 10, \"event\": \"submit\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.RULE_FAILED.getCode()))
                .andExpect(jsonPath("$.data.failed_rules[0].rule_uuid").value("rule-deny"))
                .andExpect(jsonPath("$.data.saga_executed").value(false));

        assertThat(audits).anyMatch(entry -> "validation_reject".equals(entry.getOperation())
                && entry.getAfterValue().contains("\"code\":42201"));
        // 未进入流转事务：outbox 仅建单 DOC_CREATED 行、无流转事件；历史行仅创建 seq=1
        assertThat(template.getRange("event_outbox")).hasSize(1);
        assertThat(template.getRange("event_outbox"))
                .noneMatch(row -> "DOC_STATUS_CHANGED".equals(row.getString("event_type")));
        assertThat(template.getRange("doc_data_history")).hasSize(1);
    }

    // ==================== AC-4：幂等重放 ====================

    @Test
    @DisplayName("幂等重放：同键回放首次结果，history 只增一条（design/05 §四 5.2）")
    void idempotentReplay_singleHistoryRow() throws Exception {
        String uuid = createPo(100);
        String key = uuid + ":transition:tok-1";
        String request = "{\"target_status\": 10, \"event\": \"submit\"}";

        MvcResult first = mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", key)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", key)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstData = mapper.readTree(first.getResponse().getContentAsString()).get("data");
        JsonNode secondData = mapper.readTree(second.getResponse().getContentAsString()).get("data");
        assertThat(secondData.get("to_status").asLong()).isEqualTo(firstData.get("to_status").asLong());
        assertThat(secondData.get("version").asLong()).isEqualTo(firstData.get("version").asLong());
        // 重放不再写入：历史行仅 创建(1) + 首次流转(2)
        assertThat(template.getRange("doc_data_history")).hasSize(2);

        // 非法键格式 → 40002
        mockMvc.perform(post("/api/v1/data/doc/{doc}/{uuid}/transition", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "bad key with space")
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.TYPE_INVALID.getCode()));
    }

    // ==================== AC-3：模拟锁失效双写 → CAS 拦截 ====================

    @Test
    @DisplayName("模拟锁失效双写：过期 fromStatus 提交被 CAS 拦截，无重复历史（design/05 §四 3.1/3.5）")
    void staleDoubleWrite_casRejects() throws Exception {
        String uuid = createPo(100);
        // 正常流转一次：100 → 10
        docDataStore.commitTransition(DOC, uuid, 100L, 10L, "alice", "submit", null);
        // 模拟锁失效后的迟到双写：仍以旧状态 100 发起 → 条件更新失败
        assertThatThrownBy(() -> docDataStore.commitTransition(DOC, uuid, 100L, 10L, "bob", "submit", null))
                .isInstanceOf(TsConditionCheckException.class);
        // 无脏写：主行仍为 10/version=2，历史行无重复 seq
        mockMvc.perform(get("/api/v1/data/doc/{doc}/{uuid}", DOC, uuid))
                .andExpect(jsonPath("$.data.current_status").value(10))
                .andExpect(jsonPath("$.data.version").value(2));
        List<Long> seqs = new ArrayList<>();
        template.getRange("doc_data_history").forEach(row ->
                seqs.add(row.getPrimaryKey().get("seq") instanceof Number number ? number.longValue() : null));
        assertThat(seqs).containsExactlyInAnyOrder(1L, 2L);
    }

    // ==================== CRUD 状态约束（leader 补项③：40008/40009 出口） ====================

    @Test
    @DisplayName("状态编辑约束：已提交态修改 40008 / 非暂存删除 40009（prd/04 状态编辑约束）")
    void crudStatusConstraints_40008_40009() throws Exception {
        String uuid = createPo(100);
        // 暂存态可改
        mockMvc.perform(put("/api/v1/data/doc/{doc}/{uuid}", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\": {\"amount\": 200}, \"version\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        // 提交后：修改 40008、删除 40009
        docDataStore.commitTransition(DOC, uuid, 100L, 10L, "alice", "submit", null);
        mockMvc.perform(put("/api/v1/data/doc/{doc}/{uuid}", DOC, uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\": {\"amount\": 300}, \"version\": 3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STATE_NOT_EDITABLE.getCode()));
        mockMvc.perform(delete("/api/v1/data/doc/{doc}/{uuid}", DOC, uuid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ONLY_DRAFT_DELETE.getCode()));
        // 暂存态删除成功（逻辑删后详情 40401）
        String draft = createPo(100);
        mockMvc.perform(delete("/api/v1/data/doc/{doc}/{uuid}", DOC, draft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        mockMvc.perform(get("/api/v1/data/doc/{doc}/{uuid}", DOC, draft))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("创建规则失败 → 42201 短路不落库（create_rules 事务外校验）")
    void createRuleFailed_noRowWritten() throws Exception {
        configGateway.rules.put("rule-create-deny", new RuleDefinition(
                "rule-create-deny", "拒绝创建", "expression", "false", null, true));
        configGateway.bindings = new DocRuleBindings(DOC, List.of("rule-create-deny"), Map.of());

        mockMvc.perform(post("/api/v1/data/doc/{doc}", DOC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\": {\"amount\": 50}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.RULE_FAILED.getCode()))
                .andExpect(jsonPath("$.data.failed_rules[0].rule_uuid").value("rule-create-deny"));
        assertThat(template.getRange("doc_data")).isEmpty();
    }

    // ==================== 装配工具 ====================

    /** 创建一张暂存态单据并返回 uuid */
    private String createPo(int amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/data/doc/{doc}", DOC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice")
                        .header("X-User-Dept", "sales")
                        .content("{\"attributes\": {\"amount\": " + amount + "}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current_status").value(100))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("uuid").asText();
    }

    /** 外销订单雏形定义：100 暂存/10 已提交/20 已审核/99 已关闭（prd/12 §四口径） */
    private static DocDefinition poDefinition() {
        Map<Long, DocStateDef> statusDefs = new LinkedHashMap<>();
        statusDefs.put(100L, new DocStateDef(100L, "暂存", true, false));
        statusDefs.put(10L, new DocStateDef(10L, "已提交", false, false));
        statusDefs.put(20L, new DocStateDef(20L, "已审核", false, false));
        statusDefs.put(99L, new DocStateDef(99L, "已关闭", false, true));
        List<DocAttribute> attributes = List.of(
                new DocAttribute("amount", "金额", "number", true, false, null, null, List.of()),
                new DocAttribute("customer", "客户", "ref", false, false, "customer", "name", List.of()));
        return new DocDefinition(DOC, "外销订单", 1L, attributes, statusDefs);
    }

    /** 内存桩配置网关（定义/状态机/绑定/规则/事务配置五域；tx_config 未配置=无 Saga） */
    private static final class FakeDocConfigGateway implements DocConfigGateway {

        private DocDefinition definition;
        private DocStateMachine stateMachine;
        private DocRuleBindings bindings;
        private final Map<String, RuleDefinition> rules = new LinkedHashMap<>();
        private final List<TxPlan> txPlans = new ArrayList<>();

        @Override
        public DocDefinition getDocSchema(String docCode) {
            return definition;
        }

        @Override
        public DocStateMachine getStateMachine(String docCode) {
            return stateMachine == null ? new DocStateMachine(docCode, Map.of()) : stateMachine;
        }

        @Override
        public DocRuleBindings getDocRuleBindings(String docCode) {
            return bindings == null ? new DocRuleBindings(docCode, List.of(), Map.of()) : bindings;
        }

        @Override
        public RuleDefinition getRule(String uuid) {
            RuleDefinition rule = rules.get(uuid);
            if (rule == null) {
                throw new com.example.common.exception.BizException(
                        ErrorCode.NOT_FOUND, "规则不存在: " + uuid);
            }
            return rule;
        }

        @Override
        public List<TxPlan> getTxPlans(String docCode) {
            return List.copyOf(txPlans);
        }
    }
}
