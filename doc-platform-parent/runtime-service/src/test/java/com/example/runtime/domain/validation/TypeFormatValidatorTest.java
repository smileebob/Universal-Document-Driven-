package com.example.runtime.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.AttributeType;
import com.example.runtime.domain.model.EntitySchema;
import com.example.runtime.domain.model.FieldError;

/** 字段类型/长度/精度校验：INTEGER/DECIMAL 与 STRING 长度（META-01/DATA-01）。 */
class TypeFormatValidatorTest {

    private final TypeFormatValidator validator = new TypeFormatValidator();

    private static AttributeDef attr(String code, AttributeType type, int maxLength, int precision) {
        return new AttributeDef(code, code, type, false, false, null,
                maxLength, precision, null, true, null, 0);
    }

    private List<FieldError> validate(AttributeDef attr, Object value) {
        EntitySchema schema = new EntitySchema("sample", "样例",
                EntitySchema.STATUS_ACTIVE, List.of(attr));
        return validator.validate(new ValidationContext(schema, Map.of(attr.getCode(), value),
                false, null));
    }

    @Test
    @DisplayName("INTEGER：小数被拒绝，整数通过")
    void integerRejectsFraction() {
        AttributeDef qty = attr("qty", AttributeType.INTEGER, 0, 0);
        assertThat(validate(qty, 1.5d)).hasSize(1);
        assertThat(validate(qty, 2L)).isEmpty();
    }

    @Test
    @DisplayName("DECIMAL：超出 precision 被拒绝，精度内通过")
    void decimalRespectsPrecision() {
        AttributeDef price = attr("price", AttributeType.DECIMAL, 0, 2);
        assertThat(validate(price, 10.123d)).hasSize(1);
        assertThat(validate(price, 10.12d)).isEmpty();
        assertThat(validate(price, 10)).isEmpty();
    }

    @Test
    @DisplayName("STRING：maxLength 生效")
    void stringRespectsMaxLength() {
        AttributeDef code = attr("code", AttributeType.STRING, 5, 0);
        assertThat(validate(code, "TOOLONG")).hasSize(1);
        assertThat(validate(code, "A1")).isEmpty();
    }

    @Test
    @DisplayName("DATE：非法日期被拒绝")
    void dateValidated() {
        AttributeDef date = attr("date", AttributeType.DATE, 0, 0);
        assertThat(validate(date, "2026-13-40")).hasSize(1);
        assertThat(validate(date, "2026-10-23")).isEmpty();
    }
}
