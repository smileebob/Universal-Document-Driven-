package com.example.runtime.domain.model;

import java.math.BigDecimal;

/**
 * unique 属性值归一化工具（unique_index 占位键的原文口径，design/03 §5.2）。
 *
 * <p>归一化规则：NUMBER 去尾零（80000.00 与 80000 同值同键）、
 * BOOLEAN 小写 true/false、其余按 String.valueOf 透传。</p>
 */
public final class UniqueValues {

    private UniqueValues() {
    }

    /**
     * 归一化属性值为占位键原文。
     *
     * @param value 属性值（非 null，调用方保证）
     * @return 归一化串
     */
    public static String normalize(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        return String.valueOf(value);
    }
}
