package com.example.runtime.domain.event;

/**
 * 操作日志投影写入端口（history / event_outbox / audit_log）。
 *
 * <p>三类投影均使用确定性主键 + EXPECT_NOT_EXIST；主键已存在时必须核对
 * 内容一致（幂等重放），内容不一致抛冲突转人工，禁止覆盖已发送事件或
 * 不可变审计。</p>
 */
public interface DocProjectionStore {

    /** 投影状态历史（仅创建/流转调用；同 seq 已存在须内容一致）。 */
    void putHistoryIfAbsent(DocOperationLog log);

    /** 投影 outbox 事件（无事件日志跳过；稳定 eventId）。 */
    void putOutboxIfAbsent(DocOperationLog log);

    /** 投影审计日志（确定性 log_id = op:{operationId}）。 */
    void putAuditIfAbsent(DocOperationLog log);

    /** 投影内容与既有不可变行冲突（需人工核对）。 */
    class ProjectionConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ProjectionConflictException(String message) {
            super(message);
        }
    }
}
