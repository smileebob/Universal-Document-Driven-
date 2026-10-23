package com.example.runtime.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 一次已提交单据操作的快照上下文（写操作日志用）。
 *
 * <p>在进入存储层前冻结：before/after 属性、状态、版本、操作人、事件类型与
 * Saga 关联。日志行一旦写入即不可变，后续历史/事件/审计投影只读该快照，
 * 不再回读主行最新状态。</p>
 */
public record DocOperationContext(
        String operationId,
        String operation,
        String operator,
        String traceId,
        String sagaId,
        String event,
        String eventType,
        long schemaVersion,
        long beforeVersion,
        long beforeStatus,
        Map<String, Object> beforeAttributes,
        long afterVersion,
        long afterStatus,
        Map<String, Object> afterAttributes,
        List<Map<String, Object>> afterRelations,
        boolean deletedAfter,
        long occurredAt) {

    public static final String OP_CREATE = "create";
    public static final String OP_UPDATE = "update";
    public static final String OP_DELETE = "delete";
    public static final String OP_TRANSITION = "transition";

    public DocOperationContext {
        beforeAttributes = beforeAttributes == null ? Map.of() : beforeAttributes;
        afterAttributes = afterAttributes == null ? Map.of() : afterAttributes;
        afterRelations = afterRelations == null ? List.of() : afterRelations;
    }

    /** 是否产生领域事件（普通属性修改/删除可为 false）。 */
    public boolean hasEvent() {
        return eventType != null && !eventType.isBlank();
    }

    /** 是否产生状态历史行（创建与状态流转）。 */
    public boolean changesStatus() {
        return OP_CREATE.equals(operation) || OP_TRANSITION.equals(operation);
    }
}
