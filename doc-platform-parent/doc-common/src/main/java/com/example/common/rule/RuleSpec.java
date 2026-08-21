package com.example.common.rule;

/**
 * 规则求值规格（执行器输入，meta/runtime 共用，design/08 §四）。
 *
 * <p>rule_type：{@code expression}（SpEL 只读沙箱求值）/ {@code interface}
 * （接口型占位，阶段二跳过不执行）。</p>
 */
public class RuleSpec {

    /** 表达式型规则 */
    public static final String TYPE_EXPRESSION = "expression";

    /** 接口型规则（占位） */
    public static final String TYPE_INTERFACE = "interface";

    /** 表达式最大长度（design/08 §四安全约束） */
    public static final int MAX_EXPRESSION_LENGTH = 500;

    private final String uuid;
    private final String name;
    private final String ruleType;
    private final String expression;
    private final boolean enabled;

    public RuleSpec(String uuid, String name, String ruleType, String expression, boolean enabled) {
        this.uuid = uuid;
        this.name = name;
        this.ruleType = ruleType;
        this.expression = expression;
        this.enabled = enabled;
    }

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getRuleType() {
        return ruleType;
    }

    public String getExpression() {
        return expression;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isExpressionType() {
        return TYPE_EXPRESSION.equalsIgnoreCase(ruleType);
    }
}
