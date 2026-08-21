package com.example.runtime.domain.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.FieldError;

/**
 * 校验链第①步：必填校验（required=true 的属性非空，PRD 05 RD-01）。
 */
public final class RequiredValidator implements AttributeValidator {

    @Override
    public List<FieldError> validate(ValidationContext context) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> attributes = context.getAttributes();
        for (AttributeDef def : context.getSchema().getAttributes()) {
            if (!def.isRequired()) {
                continue;
            }
            Object value = attributes.get(def.getCode());
            if (value == null || (value instanceof String s && s.isBlank())) {
                errors.add(new FieldError(def.getCode(), def.getName(),
                        "「" + def.getName() + "」为必填项"));
            }
        }
        return errors;
    }
}
