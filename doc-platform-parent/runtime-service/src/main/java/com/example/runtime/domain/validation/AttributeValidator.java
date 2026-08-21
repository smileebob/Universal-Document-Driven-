package com.example.runtime.domain.validation;

import java.util.List;

import com.example.runtime.domain.model.FieldError;

/**
 * 属性级校验器（五类校验链的单步抽象）。
 *
 * <p>短路顺序（PRD 05 RD-01）：必填 → 类型/格式 → ref → unique → 规则；
 * 每步聚合本步全部字段错误后由 {@link DataValidationChain} 统一抛出。</p>
 */
public interface AttributeValidator {

    /**
     * 执行本步校验。
     *
     * @param context 校验上下文
     * @return 本步字段错误列表（空=通过）
     */
    List<FieldError> validate(ValidationContext context);
}
