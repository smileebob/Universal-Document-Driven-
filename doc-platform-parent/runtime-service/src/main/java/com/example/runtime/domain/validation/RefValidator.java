package com.example.runtime.domain.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.AttributeType;
import com.example.runtime.domain.model.FieldError;
import com.example.runtime.domain.port.RefChecker;

/**
 * 校验链第③步：ref 校验（REF 属性值必须是被引用实体的有效且未停用数据 uuid，
 * PRD 05 RD-01；存在性判定走主键点查，TS-03）。
 */
public final class RefValidator implements AttributeValidator {

    private final RefChecker refChecker;

    public RefValidator(RefChecker refChecker) {
        this.refChecker = refChecker;
    }

    @Override
    public List<FieldError> validate(ValidationContext context) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> attributes = context.getAttributes();
        for (AttributeDef def : context.getSchema().getAttributes()) {
            if (def.getType() != AttributeType.REF) {
                continue;
            }
            Object value = attributes.get(def.getCode());
            if (value == null) {
                continue;
            }
            String targetUuid = String.valueOf(value);
            String targetEntityCode = def.getRefEntityCode();
            if (targetEntityCode == null || targetEntityCode.isBlank()
                    || !refChecker.isActiveData(targetEntityCode, targetUuid)) {
                errors.add(new FieldError(def.getCode(), def.getName(),
                        "「" + def.getName() + "」引用的数据不存在或已停用"));
            }
        }
        return errors;
    }
}
