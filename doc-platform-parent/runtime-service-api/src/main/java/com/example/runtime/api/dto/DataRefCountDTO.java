package com.example.runtime.api.dto;

import java.io.Serializable;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 数据引用计数 DTO（引用保护契约，design/08 §6.1 场景 4）。
 *
 * <p>占位骨架：统计口径（有效数据量/被引用计数）阶段一按 RD-03/RD-06 充实。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DataRefCountDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义类型：entity / doc */
    private String targetType;

    /** 定义编码 */
    private String targetCode;

    /** 该定义下有效（未逻辑删除）数据量 */
    private Long validDataCount;

    /** 被其他数据引用的计数（relations 反查） */
    private Long referencedCount;

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = targetCode;
    }

    public Long getValidDataCount() {
        return validDataCount;
    }

    public void setValidDataCount(Long validDataCount) {
        this.validDataCount = validDataCount;
    }

    public Long getReferencedCount() {
        return referencedCount;
    }

    public void setReferencedCount(Long referencedCount) {
        this.referencedCount = referencedCount;
    }
}
