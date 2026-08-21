package com.example.common.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则执行器单测（RE-05 安全机制，任务书 t8「规则执行器超时/沙箱越权
 * （禁反射/禁类型构造）单测」）。
 *
 * <p>覆盖：pass/fail 主路径、停用跳过、interface 型占位跳过、
 * SpEL 只读沙箱拒绝 T() 类型引用与构造器调用（fail 不泄漏堆栈）、
 * 表达式长度超限、编译缓存命中、有界线程池超时（reason=timeout）、
 * 在线测试入口非法 JSON 兜底。</p>
 */
class RuleExecutorTest {

    private RuleExecutor executor;

    @BeforeEach
    void setUp() {
        // 默认 2s 超时、核心 1 / 最大 2 / 队列 16 / 缓存 64
        executor = new RuleExecutor(2000, 1, 2, 16, 64);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private static RuleSpec expression(String uuid, String expression) {
        return new RuleSpec(uuid, "规则-" + uuid, RuleSpec.TYPE_EXPRESSION, expression, true);
    }

    @Test
    @DisplayName("表达式 true/false → pass/fail，变量可读")
    void evaluate_passAndFail() {
        RuleEvalResult pass = executor.execute(expression("r1", "#amount > 10"),
                Map.of("amount", 20));
        assertThat(pass.isPassed()).isTrue();
        assertThat(pass.isSkipped()).isFalse();
        assertThat(pass.getRuleUuid()).isEqualTo("r1");

        RuleEvalResult fail = executor.execute(expression("r2", "#amount > 10"),
                Map.of("amount", 5));
        assertThat(fail.isPassed()).isFalse();
        assertThat(fail.getReason()).contains("false");
    }

    @Test
    @DisplayName("停用规则 → skip（不计入失败，design/08 §四）")
    void disabledRule_skipped() {
        RuleSpec disabled = new RuleSpec("r-off", "停用规则",
                RuleSpec.TYPE_EXPRESSION, "false", false);
        RuleEvalResult result = executor.execute(disabled, Map.of());
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getReason()).contains("停用");
    }

    @Test
    @DisplayName("interface 型规则 → 占位 skip（短路顺序由绑定编排保证）")
    void interfaceRule_skipped() {
        RuleSpec iface = new RuleSpec("r-iface", "接口型", RuleSpec.TYPE_INTERFACE, null, true);
        RuleEvalResult result = executor.execute(iface, Map.of());
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.getReason()).contains("占位");
    }

    @Test
    @DisplayName("沙箱越权：T() 类型引用被拒 → fail 且不泄漏堆栈")
    void sandbox_typeReference_rejected() {
        RuleEvalResult result = executor.execute(
                expression("r-t", "T(java.lang.Runtime).getRuntime() != null"), Map.of());
        assertThat(result.isPassed()).isFalse();
        assertThat(result.isSkipped()).isFalse();
        assertThat(result.getReason()).isEqualTo("表达式求值异常");
    }

    @Test
    @DisplayName("沙箱越权：构造器调用被拒 → fail")
    void sandbox_constructor_rejected() {
        RuleEvalResult result = executor.execute(
                expression("r-new", "new java.lang.ProcessBuilder({'cmd'}).start() != null"),
                Map.of());
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason()).isEqualTo("表达式求值异常");
    }

    @Test
    @DisplayName("沙箱越权：反射调用路径被拒 → fail")
    void sandbox_reflection_rejected() {
        RuleEvalResult result = executor.execute(
                expression("r-reflect",
                        "T(java.lang.Class).forName('java.lang.Runtime').getMethod('getRuntime').invoke(null) != null"),
                Map.of());
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason()).isEqualTo("表达式求值异常");
    }

    @Test
    @DisplayName("表达式为空/超长 → fail（长度守卫 500）")
    void expressionGuards() {
        RuleEvalResult blank = executor.execute(expression("r-blank", " "), Map.of());
        assertThat(blank.isPassed()).isFalse();
        assertThat(blank.getReason()).contains("为空");

        String oversized = "1==1||".repeat(120);
        assertThat(oversized.length()).isGreaterThan(RuleSpec.MAX_EXPRESSION_LENGTH);
        RuleEvalResult tooLong = executor.execute(expression("r-long", oversized), Map.of());
        assertThat(tooLong.isPassed()).isFalse();
        assertThat(tooLong.getReason()).contains("长度超限");
    }

    @Test
    @DisplayName("语法非法表达式 → fail 表达式语法非法")
    void invalidSyntax_failed() {
        RuleEvalResult result = executor.execute(expression("r-syntax", "#a +++ (((("), Map.of());
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason()).isEqualTo("表达式语法非法");
    }

    @Test
    @DisplayName("编译缓存：同 uuid+内容重复执行命中，改内容后按新表达式求值")
    void compiledCache_hitAndContentChange() {
        RuleSpec spec = expression("r-cache", "#x > 0");
        assertThat(executor.execute(spec, Map.of("x", 1)).isPassed()).isTrue();
        assertThat(executor.execute(spec, Map.of("x", 1)).isPassed()).isTrue();
        // 同 uuid 但内容变更 → md5 不同 key，不命中旧编译产物
        RuleSpec changed = expression("r-cache", "#x < 0");
        assertThat(executor.execute(changed, Map.of("x", 1)).isPassed()).isFalse();
    }

    @Test
    @DisplayName("求值超时 → fail reason=timeout（有界线程池 + 2s 上限，测试用 1ms）")
    void evaluationTimeout_failed() throws Exception {
        RuleExecutor tight = null;
        try {
            tight = new RuleExecutor(1, 1, 1, 1, 8);
            // 大列表 selection 求值，远超 1ms 超时窗
            List<Integer> big = new ArrayList<>(500_000);
            for (int i = 0; i < 500_000; i++) {
                big.add(i);
            }
            RuleEvalResult result = tight.execute(
                    expression("r-slow", "#big.?[#this >= 0].size() > 0"),
                    Map.of("big", big));
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getReason()).isEqualTo("timeout");
        } finally {
            if (tight != null) {
                tight.close();
            }
        }
    }

    @Test
    @DisplayName("在线测试入口：非法 JSON 上下文 → fail 兜底；合法 JSON 正常求值")
    void testEntry_invalidJsonFallback() {
        RuleSpec spec = expression("r-test", "#amount > 0");
        RuleEvalResult bad = executor.test(spec, "not-a-json");
        assertThat(bad.isPassed()).isFalse();
        assertThat(bad.getReason()).contains("JSON 非法");

        RuleEvalResult good = executor.test(spec, "{\"amount\": 5}");
        assertThat(good.isPassed()).isTrue();
    }
}
