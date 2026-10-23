package com.example.runtime.domain.saga;

/**
 * Saga 执行状态机（design/05 §saga_execution：EXECUTING → COMPLETED /
 * COMPENSATING → COMPENSATED / DEAD）。
 *
 * <p>DEAD 为终态告警位：冲正失败后真相落 saga_dead_letter 表（TX-03），
 * 人工处置经死信接口（retry/resolve）。</p>
 */
public enum SagaStatus {

    /** 正向执行中 */
    EXECUTING("EXECUTING"),

    /** 外部步骤全部成功，本地单据提交待收口（崩溃恢复据此决定提交或冲正） */
    ACTIONS_SUCCEEDED("ACTIONS_SUCCEEDED"),

    /** 外部步骤成功且本地单据已提交 */
    COMPLETED("COMPLETED"),

    /** 正向失败，逆序冲正中 */
    COMPENSATING("COMPENSATING"),

    /** 冲正完成（单据状态保持原值，42202 返回） */
    COMPENSATED("COMPENSATED"),

    /** 冲正失败转死信（ERROR 告警，人工处置） */
    DEAD("DEAD");

    private final String code;

    SagaStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 编码反查（宽容模式：未知编码返回 null 由调用方处置）。
     *
     * @param code 状态编码
     * @return 枚举或 null
     */
    public static SagaStatus fromCode(String code) {
        for (SagaStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
