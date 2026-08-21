package com.example.runtime.domain.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.runtime.domain.model.FieldError;

/**
 * 五类校验链编排（短路顺序，PRD 05 RD-01）：
 * ① 必填 → ② 类型/格式 → ③ ref → ④ unique（CAS 占位，service 层编排）→ ⑤ 规则。
 *
 * <p>本链承载 ①②③⑤ 四步（④唯一约束是强一致占位流程而非纯校验，
 * 由 service 层 UniqueConstraintService 在写入路径执行）；
 * 每步聚合本步全部字段错误，命中即抛 {@link BizException} 并短路后续步骤，
 * 错误明细经 data.field_errors 返回（PRD 11 错误响应约定）。</p>
 */
public final class DataValidationChain {

    /** 校验步骤：步骤校验器 + 对应错误码 */
    private final List<Step> steps;

    private DataValidationChain(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * 构造标准校验链（①必填40001 → ②类型40002 → ③ref40004 → ⑤规则42201）。
     *
     * @param refValidator  ref 校验器（依赖 RefChecker 端口）
     * @param ruleValidator 规则校验器（本期 NoopRuleValidator 扩展点位）
     * @return 校验链
     */
    public static DataValidationChain standard(RefValidator refValidator, RuleValidator ruleValidator) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(new RequiredValidator(), ErrorCode.REQUIRED_MISSING));
        steps.add(new Step(new TypeFormatValidator(), ErrorCode.TYPE_INVALID));
        steps.add(new Step(refValidator, ErrorCode.REF_INVALID));
        steps.add(new Step(ruleValidator, ErrorCode.RULE_FAILED));
        return new DataValidationChain(steps);
    }

    /**
     * 执行校验链（短路：首个失败步骤即抛出，聚合本步全部字段错误）。
     *
     * @param context 校验上下文
     * @throws BizException 携带 field_errors 明细的业务异常
     */
    public void validate(ValidationContext context) {
        for (Step step : steps) {
            List<FieldError> errors = step.validator().validate(context);
            if (!errors.isEmpty()) {
                throw toException(step.errorCode(), errors);
            }
        }
    }

    /**
     * 字段错误列表 → BizException（data = {field_errors: [...]}）。
     *
     * @param errorCode 步骤错误码
     * @param errors    字段错误列表（非空）
     * @return 业务异常
     */
    public static BizException toException(ErrorCode errorCode, List<FieldError> errors) {
        List<Map<String, String>> details = errors.stream()
                .map(error -> Map.of(
                        "field", error.field(),
                        "name", error.name(),
                        "message", error.message()))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(FieldError.DATA_KEY_FIELD_ERRORS, details);
        return new BizException(errorCode, errors.get(0).message(), data);
    }

    /** 步骤定义 */
    private record Step(AttributeValidator validator, ErrorCode errorCode) {
    }
}
