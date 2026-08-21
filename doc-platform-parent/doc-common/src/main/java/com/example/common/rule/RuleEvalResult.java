package com.example.common.rule;

/**
 * 规则求值结果（执行器输出）。
 *
 * <p>语义（design/08 §四）：</p>
 * <ul>
 *   <li>passed=true：规则通过；</li>
 *   <li>passed=false + reason：规则失败（表达式 false、求值异常、超时
 *       reason={@code timeout}、表达式非法等），异常不泄漏堆栈；</li>
 *   <li>skipped=true：跳过执行（停用规则告警跳过 / interface 型占位跳过），
 *       不计入失败。</li>
 * </ul>
 */
public class RuleEvalResult {

    private final String ruleUuid;
    private final String ruleName;
    private final boolean passed;
    private final boolean skipped;
    private final String reason;

    private RuleEvalResult(String ruleUuid, String ruleName, boolean passed,
                           boolean skipped, String reason) {
        this.ruleUuid = ruleUuid;
        this.ruleName = ruleName;
        this.passed = passed;
        this.skipped = skipped;
        this.reason = reason;
    }

    /** 规则通过 */
    public static RuleEvalResult pass(RuleSpec spec) {
        return new RuleEvalResult(spec.getUuid(), spec.getName(), true, false, null);
    }

    /** 规则失败（附原因，不含堆栈） */
    public static RuleEvalResult fail(RuleSpec spec, String reason) {
        return new RuleEvalResult(spec.getUuid(), spec.getName(), false, false, reason);
    }

    /** 跳过执行（停用/接口型占位） */
    public static RuleEvalResult skip(RuleSpec spec, String reason) {
        return new RuleEvalResult(spec.getUuid(), spec.getName(), true, true, reason);
    }

    public String getRuleUuid() {
        return ruleUuid;
    }

    public String getRuleName() {
        return ruleName;
    }

    public boolean isPassed() {
        return passed;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "RuleEvalResult{uuid=" + ruleUuid + ", passed=" + passed
                + ", skipped=" + skipped + ", reason=" + reason + "}";
    }
}
