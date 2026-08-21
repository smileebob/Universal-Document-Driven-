package com.example.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * ApiResponse 单元测试（任务 #20：traceId MDC 回填断点修复）。
 *
 * <p>覆盖矩阵：MDC 有 traceId 时 ok()/fail() 自动回填；MDC 为空保持 null（非 web 场景安全）；
 * 显式 setTraceId 可覆盖。</p>
 */
class ApiResponseTest {

    @AfterEach
    void tearDown() {
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("MDC 有 traceId：ok()/fail() 自动回填")
    void backfillFromMdc() {
        MDC.put("traceId", "trace-mdc-001");
        assertThat(ApiResponse.ok("data").getTraceId()).isEqualTo("trace-mdc-001");
        assertThat(ApiResponse.fail(42201, "rejected").getTraceId()).isEqualTo("trace-mdc-001");
    }

    @Test
    @DisplayName("MDC 为空：traceId 保持 null（非 web/异步场景安全）")
    void nullWhenMdcEmpty() {
        MDC.remove("traceId");
        assertThat(ApiResponse.ok(null).getTraceId()).isNull();
    }

    @Test
    @DisplayName("显式 setTraceId 覆盖构造回填")
    void explicitSetOverrides() {
        MDC.put("traceId", "trace-mdc-002");
        ApiResponse<String> resp = ApiResponse.ok("x");
        resp.setTraceId("trace-override");
        assertThat(resp.getTraceId()).isEqualTo("trace-override");
    }

    @Test
    @DisplayName("基础语义：code/message/data/isSuccess")
    void basicSemantics() {
        ApiResponse<String> ok = ApiResponse.ok("payload");
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.getCode()).isEqualTo(ApiResponse.CODE_SUCCESS);
        assertThat(ok.getMessage()).isEqualTo("success");
        assertThat(ok.getData()).isEqualTo("payload");

        ApiResponse<String> fail = ApiResponse.fail(40001, "bad request");
        assertThat(fail.isSuccess()).isFalse();
        assertThat(fail.getData()).isNull();
    }
}
