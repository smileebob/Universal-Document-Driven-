package com.example.common.exception;

/**
 * 业务错误码枚举（H-07 禁魔法值收口）。
 *
 * <p>错误码全集与前端文案以 prd/12-数据字典与错误码 §三 为唯一定义处（17 项错误码 + SUCCESS）；
 * HTTP 映射以 design/08-后端服务设计 §5.3 为准。业务层只抛 {@link BizException}，
 * 禁止直接操作 HTTP 响应。</p>
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, 200, "操作成功"),

    /** 「{字段名}」为必填项（校验链第①步） */
    REQUIRED_MISSING(40001, 400, "「{}」为必填项"),

    /** 「{字段名}」格式不正确（校验链第②步） */
    TYPE_INVALID(40002, 400, "「{}」格式不正确"),

    /** 不允许从「{当前态}」流转到「{目标态}」（状态机校验，SM-02） */
    ILLEGAL_TRANSITION(40003, 400, "不允许从「{}」流转到「{}」"),

    /** 「{字段名}」引用的数据不存在或已停用（校验链第③步） */
    REF_INVALID(40004, 400, "「{}」引用的数据不存在或已停用"),

    /** 「{字段名}」的值已存在（unique 约束表 CAS 失败，校验链第④步） */
    UNIQUE_CONFLICT(40005, 400, "「{}」的值已存在"),

    /** 编码已存在（定义编码重复） */
    CODE_DUPLICATE(40006, 400, "编码已存在"),

    /** 「{字段名}」不可修改（RD-02 前置校验） */
    NOT_EDITABLE(40007, 400, "「{}」不可修改"),

    /** 当前状态「{状态名}」不允许修改（RD-02 前置校验） */
    STATE_NOT_EDITABLE(40008, 400, "当前状态「{}」不允许修改"),

    /** 仅暂存状态的单据可删除（RD-07） */
    ONLY_DRAFT_DELETE(40009, 400, "仅暂存状态的单据可删除"),

    /** 该数据正被 {N} 条记录引用，无法删除/停用（RD-03 引用保护） */
    REF_PROTECTED(40010, 400, "该数据正被引用，无法删除/停用"),

    /** 发布校验未通过：{原因}（meta 发布完整性校验） */
    PUBLISH_INVALID(40011, 400, "发布校验未通过"),

    /** 数据不存在或已被删除（GetRow 主键未命中） */
    NOT_FOUND(40401, 404, "数据不存在或已被删除"),

    /** 数据已被他人修改，请刷新后重试（version 条件更新失败 / 局部事务竞争失败，TS-01/TS-04） */
    STATUS_CONFLICT(40901, 409, "数据已被他人修改，请刷新后重试"),

    /** 规则校验未通过（附失败详情 data.failed_rules） */
    RULE_FAILED(42201, 422, "规则校验未通过"),

    /** 操作失败，已回滚（Saga 正向失败且已完成冲正，附 data.failed_step） */
    TX_FAILED(42202, 422, "操作失败，已回滚"),

    /** 操作过于频繁，请稍后再试（lock:doc:{uuid} 获取失败，G-02） */
    LOCK_BUSY(42901, 429, "操作过于频繁，请稍后再试"),

    /** 系统繁忙，请稍后重试（兜底） */
    SYSTEM_ERROR(50000, 500, "系统繁忙，请稍后重试");

    /** 业务码（PRD 12 §三） */
    private final int code;

    /** 默认 HTTP 状态（design/08 §5.3 映射表） */
    private final int httpStatus;

    /** 默认文案（{} 为占位变量） */
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * 按业务码反查枚举。
     *
     * @param code 业务码
     * @return 对应枚举
     * @throws IllegalArgumentException 未登记的业务码
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        throw new IllegalArgumentException("未登记的业务错误码: " + code);
    }
}
