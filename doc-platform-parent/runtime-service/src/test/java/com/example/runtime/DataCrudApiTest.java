package com.example.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.model.ApiResponse;
import com.example.common.web.GlobalExceptionHandler;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.AttributeDTO;
import com.example.meta.api.dto.AttributeType;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.example.runtime.controller.DataController;
import com.example.runtime.controller.InternalDataController;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.port.AuditLogStore;
import com.example.runtime.infrastructure.meta.MetaSchemaGateway;
import com.example.runtime.infrastructure.tablestore.TablestoreAuditLogStore;
import com.example.runtime.infrastructure.tablestore.TablestoreEntityDataRepository;
import com.example.runtime.infrastructure.tablestore.TablestoreIdempotencyStore;
import com.example.runtime.infrastructure.tablestore.TablestoreUniqueIndexStore;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.DataService;
import com.example.runtime.service.IdempotencyService;
import com.example.runtime.service.UniqueConstraintService;
import com.example.common.infrastructure.cache.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据 CRUD 全链路验收（步骤 18 测试出口核心用例）：
 * standalone MockMvc + InMemoryTablestore 仿真层 + Mockito 桩 MetaSchemaClient。
 *
 * <p>覆盖：RD-01~04 CRUD 主干、五类校验链短路（40001/40002/40004/40005）、
 * 乐观锁 40901、唯一占位强一致（换值释放/逻辑删保留不释放）、引用保护 40010、
 * 幂等三态（重放成功/重放原错误/PROCESSING 42901/跨接口撞键）、审计旁路失败不阻断、
 * internal 契约（count/exists）。</p>
 */
class DataCrudApiTest {

    private InMemoryTablestore template;
    private ObjectMapper mapper;
    private MetaSchemaClient metaSchemaClient;
    private TablestoreEntityDataRepository dataRepository;
    private TablestoreIdempotencyStore idempotencyStore;
    private UniqueConstraintService uniqueConstraintService;
    private DataService dataService;
    private MockMvc mockMvc;
    private final AtomicInteger auditCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        metaSchemaClient = mock(MetaSchemaClient.class);
        when(metaSchemaClient.getEntitySchema("customer")).thenReturn(ApiResponse.ok(customerSchema()));
        when(metaSchemaClient.getEntitySchema("department")).thenReturn(ApiResponse.ok(departmentSchema()));
        when(metaSchemaClient.getEntitySchema("ghost"))
                .thenReturn(ApiResponse.fail(40401, "实体定义不存在"));

        MetaSchemaGateway gateway = new MetaSchemaGateway(metaSchemaClient, mock(CacheService.class), mapper);
        dataRepository = new TablestoreEntityDataRepository(template, mapper);
        TablestoreUniqueIndexStore uniqueIndexStore = new TablestoreUniqueIndexStore(template);
        idempotencyStore = new TablestoreIdempotencyStore(template);
        AuditLogStore countingAuditStore = new AuditLogStore() {
            @Override
            public void append(AuditEntry entry) {
                new TablestoreAuditLogStore(template).append(entry);
                auditCount.incrementAndGet();
            }

            @Override
            public SearchResult search(QuerySpec spec) {
                return new TablestoreAuditLogStore(template).search(spec);
            }
        };
        uniqueConstraintService = new UniqueConstraintService(uniqueIndexStore);
        AuditService auditService = new AuditService(countingAuditStore, Runnable::run);
        dataService = new DataService(gateway, dataRepository, dataRepository,
                uniqueConstraintService, auditService, mapper);
        IdempotencyService idempotencyService = new IdempotencyService(idempotencyStore, mapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DataController(dataService, idempotencyService),
                        new InternalDataController(dataRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    // ==================== RD-01 新增 + 校验链 ====================

    @Test
    @DisplayName("新增成功：uuid 返回、默认值回填、详情可见且审计落库")
    void create_success_then_detail() throws Exception {
        String uuid = createCustomer("{\"name\":\"张三\",\"code\":\"C001\"}", null);
        assertThat(uuid).isNotBlank();
        assertThat(auditCount.get()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/data/customer/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.uuid").value(uuid))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.attributes.name").value("张三"))
                // NUMBER 默认值 "3" 解析为数值
                .andExpect(jsonPath("$.data.attributes.level").value(3));
    }

    @Test
    @DisplayName("校验链①必填缺失 → 40001 + field_errors")
    void create_missing_required_40001() throws Exception {
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"code\":\"C002\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.data.field_errors[0].field").value("name"));
    }

    @Test
    @DisplayName("校验链②类型格式错误 → 40002 + field_errors")
    void create_type_invalid_40002() throws Exception {
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"李四\",\"code\":\"C003\",\"level\":\"abc\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.data.field_errors[0].field").value("level"));
    }

    @Test
    @DisplayName("校验链③ref 引用不存在 → 40004")
    void create_ref_invalid_40004() throws Exception {
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"王五\",\"code\":\"C004\",\"dept\":\"no-such-uuid\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40004))
                .andExpect(jsonPath("$.data.field_errors[0].field").value("dept"));
    }

    @Test
    @DisplayName("校验链④唯一冲突 → 40005（EXPECT_NOT_EXIST CAS）")
    void create_unique_conflict_40005() throws Exception {
        createCustomer("{\"name\":\"甲\",\"code\":\"C100\"}", null);
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"乙\",\"code\":\"C100\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40005));
    }

    @Test
    @DisplayName("未声明属性拒绝 → 40002；未知实体 → 40401")
    void create_unknown_attribute_and_unknown_entity() throws Exception {
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"丙\",\"code\":\"C101\",\"foo\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));

        mockMvc.perform(post("/api/v1/data/ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== RD-02 修改 + 乐观锁 + 唯一换值 ====================

    @Test
    @DisplayName("修改成功 version+1；旧 version 重放 → 40901")
    void update_success_then_version_conflict_40901() throws Exception {
        String uuid = createCustomer("{\"name\":\"甲\",\"code\":\"C200\"}", null);

        mockMvc.perform(put("/api/v1/data/customer/" + uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"甲改\"},\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(put("/api/v1/data/customer/" + uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"甲再改\"},\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("修改缺 version → 40002（乐观锁必填）")
    void update_missing_version_40002() throws Exception {
        String uuid = createCustomer("{\"name\":\"甲\",\"code\":\"C210\"}", null);
        mockMvc.perform(put("/api/v1/data/customer/" + uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"改\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("唯一换值：新值占位成功且旧值释放（他人可再占用旧值）")
    void update_unique_swap_releases_old_value() throws Exception {
        String uuid = createCustomer("{\"name\":\"甲\",\"code\":\"C300\"}", null);
        mockMvc.perform(put("/api/v1/data/customer/" + uuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"code\":\"C301\"},\"version\":1}"))
                .andExpect(status().isOk());

        // 旧值 C300 已释放：新增可复用
        createCustomer("{\"name\":\"乙\",\"code\":\"C300\"}", null);
    }

    // ==================== RD-03 逻辑删 + 引用保护 + 唯一占位保留 ====================

    @Test
    @DisplayName("逻辑删后唯一值仍占用（design/03 §5.6 保留不释放）→ 再建同值 40005")
    void delete_keeps_unique_reservation() throws Exception {
        String uuid = createCustomer("{\"name\":\"甲\",\"code\":\"C400\"}", null);
        mockMvc.perform(delete("/api/v1/data/customer/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0));

        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attributes\":{\"name\":\"乙\",\"code\":\"C400\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40005));

        // 已删数据详情不可见
        mockMvc.perform(get("/api/v1/data/customer/" + uuid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("引用保护：被引用数据禁止删除 → 40010；详情 ref_display 展开")
    void delete_ref_protected_40010_and_ref_display() throws Exception {
        String deptUuid = createDepartment("研发部");
        String custUuid = createCustomer(
                "{\"name\":\"甲\",\"code\":\"C500\",\"dept\":\"" + deptUuid + "\"}", null);

        mockMvc.perform(delete("/api/v1/data/department/" + deptUuid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));

        mockMvc.perform(get("/api/v1/data/customer/" + custUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ref_display.dept.display_value").value("研发部"));
    }

    @Test
    @DisplayName("详情不存在 → 40401")
    void detail_not_found_40401() throws Exception {
        mockMvc.perform(get("/api/v1/data/customer/no-such-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ==================== 幂等三态 ====================

    @Test
    @DisplayName("幂等重放：同键二次提交回放首次成功结果（uuid 一致）")
    void idempotent_replay_success() throws Exception {
        String uuid1 = createCustomer("{\"name\":\"甲\",\"code\":\"C600\"}", "idem-key-1");
        // 同键重放：即使 body 不同也回放首次结果
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-key-1")
                        .content("{\"attributes\":{\"name\":\"其他人\",\"code\":\"C601\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value(uuid1));
    }

    @Test
    @DisplayName("幂等重放：首次失败固化错误快照，重放仍回原错误码")
    void idempotent_replay_error() throws Exception {
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-key-2")
                        .content("{\"attributes\":{\"code\":\"C610\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        // 重放即使 body 合法，仍回放首次错误
        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-key-2")
                        .content("{\"attributes\":{\"name\":\"甲\",\"code\":\"C610\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("幂等处理中 → 42901；同键跨接口 → 40002")
    void idempotent_processing_and_cross_api() throws Exception {
        // 模拟首次请求占位后尚未回填（PROCESSING 滞留）
        idempotencyStore.tryBegin("idem-key-3", "POST /api/v1/data/customer");

        mockMvc.perform(post("/api/v1/data/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-key-3")
                        .content("{\"attributes\":{\"name\":\"甲\",\"code\":\"C620\"}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));

        mockMvc.perform(put("/api/v1/data/customer/any-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-key-3")
                        .content("{\"attributes\":{\"name\":\"甲\"},\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    // ==================== 审计旁路 ====================

    @Test
    @DisplayName("审计写入失败仅 WARN：主流程不受影响")
    void audit_failure_does_not_break_main_flow() {
        AuditLogStore failingStore = new AuditLogStore() {
            @Override
            public void append(AuditEntry entry) {
                throw new IllegalStateException("audit down");
            }

            @Override
            public SearchResult search(QuerySpec spec) {
                throw new IllegalStateException("audit down");
            }
        };
        AuditService failingAudit = new AuditService(failingStore, Runnable::run);
        MetaSchemaGateway gateway = new MetaSchemaGateway(metaSchemaClient, mock(CacheService.class), mapper);
        DataService serviceWithFailingAudit = new DataService(gateway, dataRepository, dataRepository,
                uniqueConstraintService, failingAudit, mapper);
        assertThat(serviceWithFailingAudit.create("customer",
                java.util.Map.of("name", "甲", "code", "C700"), "u1", "d1", null, null))
                .containsKey("uuid");
    }

    // ==================== internal 契约（design/09 §2.3） ====================

    @Test
    @DisplayName("internal：count 与 exists 契约（exists 逻辑删后为 false）")
    void internal_count_and_exists() throws Exception {
        String uuid = createCustomer("{\"name\":\"甲\",\"code\":\"C800\"}", null);

        mockMvc.perform(get("/api/v1/internal/data/count/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.target_type").value("entity"))
                .andExpect(jsonPath("$.data.target_code").value("customer"))
                .andExpect(jsonPath("$.data.valid_data_count").value(1));

        mockMvc.perform(get("/api/v1/internal/data/customer/" + uuid + "/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(delete("/api/v1/data/customer/" + uuid))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/internal/data/customer/" + uuid + "/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    // ==================== 测试夹具 ====================

    private String createCustomer(String attributesJson, String idempotencyKey) throws Exception {
        var request = post("/api/v1/data/customer")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "u1")
                .header("X-User-Dept", "d1")
                .header("X-Forwarded-For", "10.0.0.1, 10.0.0.2")
                .content("{\"attributes\":" + attributesJson + "}");
        if (idempotencyKey != null) {
            request = request.header("X-Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.uuid");
    }

    private String createDepartment(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/data/department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "u1")
                        .content("{\"attributes\":{\"name\":\"" + name + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.uuid");
    }

    private EntitySchemaDTO customerSchema() {
        return schemaDto("customer", "客户",
                attr("name", "名称", AttributeType.STRING, true, false, null, 32, null, 1),
                attr("code", "编码", AttributeType.STRING, true, true, null, 32, null, 2),
                attr("level", "等级", AttributeType.NUMBER, false, false, null, null, "3", 3),
                attr("dept", "所属部门", AttributeType.REF, false, false, "department", null, null, 4));
    }

    private EntitySchemaDTO departmentSchema() {
        return schemaDto("department", "部门",
                attr("name", "名称", AttributeType.STRING, true, false, null, 32, null, 1));
    }

    private EntitySchemaDTO schemaDto(String code, String name, AttributeDTO... attributes) {
        EntitySchemaDTO dto = new EntitySchemaDTO();
        dto.setCode(code);
        dto.setName(name);
        dto.setStatus(SchemaStatus.PUBLISHED);
        dto.setVersion(1L);
        dto.setPublishedAt(System.currentTimeMillis());
        dto.setAttributes(List.of(attributes));
        return dto;
    }

    private AttributeDTO attr(String code, String name, AttributeType type, boolean required,
                              boolean unique, String refEntityCode, Integer maxLength,
                              String defaultValue, int sortOrder) {
        AttributeDTO dto = new AttributeDTO();
        dto.setCode(code);
        dto.setName(name);
        dto.setType(type);
        dto.setRequired(required);
        dto.setUnique(unique);
        dto.setRefEntityCode(refEntityCode);
        dto.setMaxLength(maxLength);
        dto.setDefaultValue(defaultValue);
        dto.setSortOrder(sortOrder);
        return dto;
    }
}
