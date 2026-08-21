package com.example.runtime.domain.validation;

import java.util.List;

import com.example.runtime.domain.model.FieldError;

/**
 * 校验链第⑤步：规则校验扩展点位（create_rules / 状态规则，阶段二 RE 系列实现）。
 *
 * <p>本期为 no-op：接口先行落位，阶段二接入 rule_lib/doc_rule_config 后
 * 以新实现替换 {@link NoopRuleValidator}，校验链编排无需改动。</p>
 */
public interface RuleValidator extends AttributeValidator {

    /**
     * 规则校验无操作实现（阶段一占位，恒通过）。
     */
    final class NoopRuleValidator implements RuleValidator {

        @Override
        public List<FieldError> validate(ValidationContext context) {
            // 阶段二接入规则引擎（RE-05 规则绑定）；本期留扩展点位，恒通过
            return List.of();
        }
    }
}
