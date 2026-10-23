package com.example.runtime.domain.validation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.runtime.domain.model.AttributeDef;
import com.example.runtime.domain.model.FieldError;

/**
 * 校验链第②步：类型/格式校验（PRD 05 RD-01、design/09 §3.4 承载口径）。
 *
 * <ul>
 *   <li>STRING：必须为字符串，maxLength&gt;0 时长度不超限；</li>
 *   <li>INTEGER：必须为数值且无小数部分；</li>
 *   <li>DECIMAL：必须为数值，precision&gt;0 时小数位不超限；</li>
 *   <li>NUMBER：必须为数值（JSON number），拒绝字符串/布尔；</li>
 *   <li>BOOLEAN：必须为布尔；</li>
 *   <li>DATE：字符串 yyyy-MM-dd 可解析（透传存储）；</li>
 *   <li>DATETIME：字符串 ISO_DATE_TIME 可解析；</li>
 *   <li>REF：必须为非空字符串（被引用数据 uuid，存在性由第③步判定）。</li>
 * </ul>
 */
public final class TypeFormatValidator implements AttributeValidator {

    @Override
    public List<FieldError> validate(ValidationContext context) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> attributes = context.getAttributes();
        for (AttributeDef def : context.getSchema().getAttributes()) {
            Object value = attributes.get(def.getCode());
            if (value == null) {
                // 缺值由必填步负责，本步跳过
                continue;
            }
            String reason = checkValue(def, value);
            if (reason != null) {
                errors.add(new FieldError(def.getCode(), def.getName(), reason));
            }
        }
        return errors;
    }

    /**
     * 单值类型/格式判定。
     *
     * @param def   属性定义
     * @param value 非 null 值
     * @return 失败原因（null=通过）
     */
    private String checkValue(AttributeDef def, Object value) {
        switch (def.getType()) {
            case STRING: {
                if (!(value instanceof String s)) {
                    return "「" + def.getName() + "」须为字符串";
                }
                if (def.getMaxLength() > 0 && s.length() > def.getMaxLength()) {
                    return "「" + def.getName() + "」长度超出上限(" + def.getMaxLength() + ")";
                }
                return null;
            }
            case INTEGER: {
                if (!(value instanceof Number) || value instanceof Boolean) {
                    return "「" + def.getName() + "」须为整数";
                }
                java.math.BigDecimal number = new java.math.BigDecimal(value.toString());
                if (number.stripTrailingZeros().scale() > 0) {
                    return "「" + def.getName() + "」须为整数";
                }
                return null;
            }
            case DECIMAL: {
                if (!(value instanceof Number) || value instanceof Boolean) {
                    return "「" + def.getName() + "」须为数值";
                }
                java.math.BigDecimal number = new java.math.BigDecimal(value.toString());
                if (def.getPrecision() > 0
                        && number.stripTrailingZeros().scale() > def.getPrecision()) {
                    return "「" + def.getName() + "」小数位超出上限(" + def.getPrecision() + ")";
                }
                return null;
            }
            case NUMBER:
                if (!(value instanceof Number) || value instanceof Boolean) {
                    return "「" + def.getName() + "」须为数值";
                }
                return null;
            case BOOLEAN:
                if (!(value instanceof Boolean)) {
                    return "「" + def.getName() + "」须为布尔值";
                }
                return null;
            case DATE: {
                if (!(value instanceof String s)) {
                    return "「" + def.getName() + "」须为 yyyy-MM-dd 日期字符串";
                }
                try {
                    LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
                    return null;
                } catch (DateTimeParseException e) {
                    return "「" + def.getName() + "」格式不正确，应为 yyyy-MM-dd";
                }
            }
            case DATETIME: {
                if (!(value instanceof String s)) {
                    return "「" + def.getName() + "」须为 ISO8601 日期时间字符串";
                }
                try {
                    java.time.OffsetDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
                    return null;
                } catch (DateTimeParseException first) {
                    try {
                        java.time.LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
                        return null;
                    } catch (DateTimeParseException e) {
                        return "「" + def.getName() + "」格式不正确，应为 ISO8601 日期时间";
                    }
                }
            }
            case REF:
                if (!(value instanceof String s) || s.isBlank()) {
                    return "「" + def.getName() + "」须为被引用数据的 uuid";
                }
                return null;
            default:
                return "「" + def.getName() + "」类型不支持";
        }
    }
}
