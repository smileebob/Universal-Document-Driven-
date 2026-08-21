package com.example.runtime.domain.model;

/**
 * 字段级校验错误明细（五类校验链的失败载体，PRD 11 错误响应 data.field_errors）。
 *
 * <p>校验失败时以列表形式聚合本步全部错误，经
 * {@code BizException(ErrorCode, message, data)} 的 data 字段返回：
 * {@code {"field_errors":[{"field","name","message"}]}}。</p>
 *
 * @param field   属性编码
 * @param name    属性名称（错误文案用）
 * @param message 具体原因文案
 */
public record FieldError(String field, String name, String message) {

    /**
     * 构造 data 载荷键常量：field_errors。
     */
    public static final String DATA_KEY_FIELD_ERRORS = "field_errors";
}
