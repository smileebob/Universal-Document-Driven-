package com.example.common.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 规则执行器（无状态公共组件，doc-common 收口，design/08 §四）。
 *
 * <p>meta-service 在线测试与 runtime-service 运行时校验共用本执行器，
 * 保证「测试即运行」语义一致。</p>
 *
 * <p>安全机制：</p>
 * <ul>
 *   <li>沙箱：{@link SimpleEvaluationContext#forReadOnlyDataBinding()} 只读数据绑定，
 *       禁 T() 类型引用 / 构造器 / Bean 引用 / 反射（StandardEvaluationContext 能力全部关闭）；</li>
 *   <li>表达式长度 ≤ {@value RuleSpec#MAX_EXPRESSION_LENGTH} 字符，超限直接按失败；</li>
 *   <li>编译产物缓存：key = rule_uuid + 内容哈希，LRU 有界（防内存膨胀）；</li>
 *   <li>超时隔离：有界线程池提交求值，{@code 2s} 超时按规则失败（reason=timeout）返回；</li>
 *   <li>异常隔离：求值异常按规则失败返回，reason 不泄漏堆栈。</li>
 * </ul>
 *
 * <p>上下文变量约定：attributes / relations / current_status / target_status /
 * creator / sys_time，表达式以 {@code #attributes['code']} 形式引用。
 * <b>relations 为引用关系列表（五元组 {source_code, source_uuid, target_code,
 * target_uuid, source_attribute_code}），非引用数据视图</b>：规则只允许引用
 * {@code #attributes}（头属性含 table 明细行），禁止经 {@code #relations} 访问
 * 目标对象属性（design/02 关系模型标准契约）。</p>
 */
public class RuleExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutor.class);

    /** 求值超时（毫秒，design/08 §四） */
    private final long timeoutMillis;

    /** 编译缓存容量（LRU 有界） */
    private final int cacheCapacity;

    /** 编译产物缓存：rule_uuid#contentHash → Expression（accessOrder LRU） */
    private final Map<String, Expression> compiledCache;

    /** 有界求值线程池（超时隔离） */
    private final ExecutorService evalPool;

    private final org.springframework.expression.spel.standard.SpelExpressionParser parser =
            new org.springframework.expression.spel.standard.SpelExpressionParser();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuleExecutor(long timeoutMillis, int poolCoreSize, int poolMaxSize,
                        int queueCapacity, int cacheCapacity) {
        this.timeoutMillis = timeoutMillis;
        this.cacheCapacity = cacheCapacity;
        this.compiledCache = Collections.synchronizedMap(new LinkedHashMap<>(
                cacheCapacity, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Expression> eldest) {
                return size() > RuleExecutor.this.cacheCapacity;
            }
        });
        this.evalPool = new ThreadPoolExecutor(poolCoreSize, poolMaxSize,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "rule-eval");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 执行单条规则（短路求值由调用方按绑定顺序编排）。
     *
     * @param spec      规则规格
     * @param variables 上下文变量（attributes/relations/current_status/target_status/creator/sys_time）
     * @return 求值结果（passed/failed/skipped）
     */
    public RuleEvalResult execute(RuleSpec spec, Map<String, Object> variables) {
        if (!spec.isEnabled()) {
            // 停用规则跳过 + 告警（design/08 §四）
            log.warn("[rule] 规则已停用，跳过执行: uuid={}, name={}", spec.getUuid(), spec.getName());
            return RuleEvalResult.skip(spec, "规则已停用");
        }
        if (!spec.isExpressionType()) {
            // interface 型阶段二占位：跳过（expression 规则先于 interface 型的短路顺序由绑定编排保证）
            return RuleEvalResult.skip(spec, "接口型规则本期占位跳过");
        }
        String expression = spec.getExpression();
        if (expression == null || expression.isBlank()) {
            return RuleEvalResult.fail(spec, "表达式为空");
        }
        if (expression.length() > RuleSpec.MAX_EXPRESSION_LENGTH) {
            return RuleEvalResult.fail(spec, "表达式长度超限（>" + RuleSpec.MAX_EXPRESSION_LENGTH + "）");
        }
        Expression compiled;
        try {
            compiled = compiledCache.computeIfAbsent(
                    spec.getUuid() + "#" + md5(expression), key -> parser.parseExpression(expression));
        } catch (RuntimeException e) {
            log.warn("[rule] 表达式编译失败: uuid={}, cause={}", spec.getUuid(), e.getMessage());
            return RuleEvalResult.fail(spec, "表达式语法非法");
        }
        // 超时隔离：有界线程池 + 2s 上限
        Callable<Boolean> task = () -> {
            // 只读沙箱：forReadOnlyDataBinding 禁 T()/构造器/Bean 引用/反射
            SimpleEvaluationContext context = SimpleEvaluationContext
                    .forReadOnlyDataBinding().build();
            variables.forEach(context::setVariable);
            Boolean value = compiled.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(value);
        };
        Future<Boolean> future;
        try {
            future = evalPool.submit(task);
        } catch (RuntimeException e) {
            // 线程池饱和：按规则失败语义返回，不阻塞主流程
            log.warn("[rule] 求值线程池饱和: uuid={}", spec.getUuid());
            return RuleEvalResult.fail(spec, "规则引擎繁忙");
        }
        try {
            boolean passed = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return passed ? RuleEvalResult.pass(spec)
                    : RuleEvalResult.fail(spec, "表达式求值结果为 false");
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[rule] 求值超时（{}ms）: uuid={}", timeoutMillis, spec.getUuid());
            return RuleEvalResult.fail(spec, "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RuleEvalResult.fail(spec, "求值被中断");
        } catch (Exception e) {
            // 异常按规则失败返回，不泄漏堆栈（design/08 §四）
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[rule] 求值异常按失败处理: uuid={}, cause={}", spec.getUuid(),
                    String.valueOf(cause.getMessage()));
            return RuleEvalResult.fail(spec, "表达式求值异常");
        }
    }

    /**
     * 规则在线测试便捷入口（meta-service RE 在线测试共用执行器）。
     *
     * @param spec       规则规格
     * @param contextJson 上下文 JSON（反序列化为变量 Map）
     * @return 求值结果
     */
    public RuleEvalResult test(RuleSpec spec, String contextJson) {
        Map<String, Object> variables;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(
                    contextJson == null || contextJson.isBlank() ? "{}" : contextJson, Map.class);
            variables = parsed;
        } catch (Exception e) {
            return RuleEvalResult.fail(spec, "测试上下文 JSON 非法");
        }
        return execute(spec, variables);
    }

    @Override
    public void close() {
        evalPool.shutdownNow();
    }

    /** 内容哈希（编译缓存 key 组成，防同 uuid 内容变更命中旧编译产物） */
    private static String md5(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必有 MD5，理论不可达
            return String.valueOf(content.hashCode());
        }
    }
}
