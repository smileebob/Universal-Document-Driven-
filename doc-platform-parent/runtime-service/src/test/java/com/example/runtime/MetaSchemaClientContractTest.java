package com.example.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import com.example.common.model.ApiResponse;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.AttributeType;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.SchemaStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.codec.Decoder;

/**
 * runtime→meta Feign 契约集成测试（阶段一出口修复补齐契约测试缺口）。
 *
 * <p>轻量方案：按 meta-service-api 契约在内嵌 HTTP stub（JDK HttpServer，
 * 零新增依赖）上打桩，经真实 Feign 栈（SpringMvcContract 解析
 * {@code @GetMapping/@PathVariable} 契约注解 + 与生产一致的 SNAKE_CASE
 * ObjectMapper 解码）验证序列化命名、请求路径与错误分支，
 * 不依赖 Nacos/真实 meta-service。</p>
 *
 * <p>覆盖：getEntitySchema 成功分支（snake_case 报文全字段反序列化，
 * 含 ref_entity_code/max_length/sort_order 嵌套字段）、40401 未命中分支、
 * 契约路径精确匹配、listPublishedEntitySchemas 列表反序列化。</p>
 */
class MetaSchemaClientContractTest {

    /** 契约 data 段（meta-service 侧 @JsonNaming(SNAKE_CASE) 序列化口径） */
    private static final String CUSTOMER_DATA = """
            {"code":"customer","name":"客户","description":"客户主档",
             "object_type":"entity","status":"PUBLISHED","version":3,
             "published_at":1755000000000,
             "attributes":[
               {"code":"name","name":"名称","type":"STRING","required":true,
                "unique":true,"max_length":64,"sort_order":2},
               {"code":"owner","name":"负责人","type":"REF","required":false,
                "unique":false,"ref_entity_code":"employee","sort_order":1}
             ]}
            """;

    /** 契约未命中报文（未发布/不存在：业务码 40401） */
    private static final String GHOST_NOT_FOUND_BODY =
            "{\"code\":40401,\"message\":\"实体定义不存在或未发布\",\"data\":null}";

    private static HttpServer server;
    private static MetaSchemaClient client;

    /** 记录 stub 收到的请求路径（契约路径断言） */
    private static final List<String> REQUEST_PATHS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/schema/entity/customer", exchange -> {
            REQUEST_PATHS.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"code\":0,\"message\":\"success\",\"data\":" + CUSTOMER_DATA + "}");
        });
        server.createContext("/api/v1/internal/schema/entity/ghost", exchange -> {
            REQUEST_PATHS.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, GHOST_NOT_FOUND_BODY);
        });
        server.createContext("/api/v1/internal/schema/entity/published", exchange -> {
            REQUEST_PATHS.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"code\":0,\"message\":\"success\",\"data\":[" + CUSTOMER_DATA + "]}");
        });
        server.start();

        // 与生产 runtime-service 一致的 SNAKE_CASE ObjectMapper（spring.jackson 全局策略）；
        // 解码器按契约泛型反序列化（等价生产 Jackson 解码路径：同一命名策略）
        ObjectMapper snakeMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        Decoder decoder = (response, type) -> {
            try (Reader reader = response.body().asReader(StandardCharsets.UTF_8)) {
                return snakeMapper.readValue(reader, snakeMapper.constructType(type));
            }
        };

        client = Feign.builder()
                .contract(new SpringMvcContract())
                .decoder(decoder)
                .target(MetaSchemaClient.class,
                        "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("getEntitySchema 成功：snake_case 契约全字段正确反序列化")
    void getEntitySchema_success() {
        ApiResponse<EntitySchemaDTO> response = client.getEntitySchema("customer");

        assertThat(response.isSuccess()).isTrue();
        EntitySchemaDTO dto = response.getData();
        assertThat(dto).isNotNull();
        assertThat(dto.getCode()).isEqualTo("customer");
        assertThat(dto.getName()).isEqualTo("客户");
        assertThat(dto.getObjectType()).isEqualTo("entity");
        assertThat(dto.getStatus()).isEqualTo(SchemaStatus.PUBLISHED);
        assertThat(dto.getVersion()).isEqualTo(3L);
        assertThat(dto.getPublishedAt()).isEqualTo(1755000000000L);
        // 嵌套属性 snake_case 字段（ref_entity_code/max_length/sort_order）不丢字段
        assertThat(dto.getAttributes()).hasSize(2);
        assertThat(dto.getAttributes().get(0).getMaxLength()).isEqualTo(64);
        assertThat(dto.getAttributes().get(0).isRequired()).isTrue();
        assertThat(dto.getAttributes().get(0).getType()).isEqualTo(AttributeType.STRING);
        assertThat(dto.getAttributes().get(1).getType()).isEqualTo(AttributeType.REF);
        assertThat(dto.getAttributes().get(1).getRefEntityCode()).isEqualTo("employee");
        assertThat(dto.getAttributes().get(1).getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("getEntitySchema 契约路径：/api/v1/internal/schema/entity/{code} 精确命中")
    void getEntitySchema_contractPath() {
        client.getEntitySchema("customer");
        assertThat(REQUEST_PATHS).contains("/api/v1/internal/schema/entity/customer");
    }

    @Test
    @DisplayName("getEntitySchema 未命中：业务码 40401、data 为 null、isSuccess=false")
    void getEntitySchema_notFound40401() {
        ApiResponse<EntitySchemaDTO> response = client.getEntitySchema("ghost");

        assertThat(response.getCode()).isEqualTo(40401);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).contains("实体定义不存在或未发布");
    }

    @Test
    @DisplayName("listPublishedEntitySchemas：列表契约反序列化（启动预热/对账场景）")
    void listPublished_success() {
        ApiResponse<List<EntitySchemaDTO>> response = client.listPublishedEntitySchemas();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getCode()).isEqualTo("customer");
        assertThat(REQUEST_PATHS).contains("/api/v1/internal/schema/entity/published");
    }
}
