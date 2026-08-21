package com.example.runtime.domain.model;

/**
 * 审计日志条目（RD-12，audit_log 行的领域投影；只增不改，design/03 §2.10）。
 *
 * <p>operation 取值以 PRD 12 §1.9 为准：create / update / delete / transition / config；
 * 操作人一律取 X-User-Id 权威值，IP 取 X-Forwarded-For 首段（design/09 §3.8）。</p>
 */
public final class AuditEntry {

    /** 日志唯一 ID（UUID v4） */
    private final String logId;

    /** 操作类型（PRD 12 §1.9） */
    private final String operation;

    /** 目标类型：entity_data / doc_data */
    private final String targetType;

    /** 目标定义编码 */
    private final String targetCode;

    /** 目标数据 uuid */
    private final String targetUuid;

    /** 操作人 */
    private final String operator;

    /** 操作时间（毫秒时间戳） */
    private final long operateTime;

    /** 变更前快照 JSON（create 为 null） */
    private final String beforeValue;

    /** 变更后快照 JSON（delete 为 null） */
    private final String afterValue;

    /** 来源 IP */
    private final String ip;

    /** 链路追踪 ID */
    private final String traceId;

    public AuditEntry(String logId, String operation, String targetType, String targetCode,
                      String targetUuid, String operator, long operateTime,
                      String beforeValue, String afterValue, String ip, String traceId) {
        this.logId = logId;
        this.operation = operation;
        this.targetType = targetType;
        this.targetCode = targetCode;
        this.targetUuid = targetUuid;
        this.operator = operator;
        this.operateTime = operateTime;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.ip = ip;
        this.traceId = traceId;
    }

    public String getLogId() {
        return logId;
    }

    public String getOperation() {
        return operation;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public String getTargetUuid() {
        return targetUuid;
    }

    public String getOperator() {
        return operator;
    }

    public long getOperateTime() {
        return operateTime;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public String getIp() {
        return ip;
    }

    public String getTraceId() {
        return traceId;
    }
}
