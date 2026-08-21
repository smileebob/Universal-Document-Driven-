package com.example.runtime.domain.model;

/**
 * 规则定义运行域模型（rule_lib 契约适配产物，规则执行器消费）。
 */
public class RuleDefinition {

    /** 表达式型规则（SpEL 只读沙箱求值） */
    public static final String TYPE_EXPRESSION = "expression";

    /** 接口型规则（阶段二占位，执行时跳过） */
    public static final String TYPE_INTERFACE = "interface";

    private final String uuid;
    private final String ruleName;
    private final String ruleType;
    private final String expression;
    private final String interfaceCode;
    private final boolean enabled;

    public RuleDefinition(String uuid, String ruleName, String ruleType,
                          String expression, String interfaceCode, boolean enabled) {
        this.uuid = uuid;
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.expression = expression;
        this.interfaceCode = interfaceCode;
        this.enabled = enabled;
    }

    public String getUuid() {
        return uuid;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public String getExpression() {
        return expression;
    }

    public String getInterfaceCode() {
        return interfaceCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 是否表达式型 */
    public boolean isExpressionType() {
        return TYPE_EXPRESSION.equalsIgnoreCase(ruleType);
    }
}
