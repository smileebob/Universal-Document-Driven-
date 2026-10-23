package com.example.runtime.infrastructure.saga;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.saga.SagaDeadLetter;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.SagaStore;

/**
 * Saga 存储 Tablestore 实现（saga_execution / saga_dead_letter，TX-02/03）。
 *
 * <p>saga_execution V1 基线无多元索引（V 文件不可变，TS-06），崩溃恢复与死信
 * 分页经 getRange 全表扫描 + 应用层过滤（行数百级规模，任务清单 3「或分区扫描」口径）。</p>
 */
@Component
public class TablestoreSagaStore implements SagaStore {

    static final String TABLE_EXECUTION = "saga_execution";
    static final String TABLE_DEAD_LETTER = "saga_dead_letter";

    private static final Logger log = LoggerFactory.getLogger(TablestoreSagaStore.class);

    private final TablestoreTemplate template;

    public TablestoreSagaStore(TablestoreTemplate template) {
        this.template = template;
    }

    // ==================== saga_execution ====================

    @Override
    public boolean createIfAbsent(SagaExecution execution) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of(execution.getDocCode()));
        columns.put("doc_uuid", TsColumnValue.of(execution.getDocUuid()));
        columns.put("from_status", TsColumnValue.of(execution.getFromStatus()));
        columns.put("to_status", TsColumnValue.of(execution.getToStatus()));
        columns.put("expected_version", TsColumnValue.of(execution.getExpectedVersion()));
        columns.put("status", TsColumnValue.of(execution.getStatus().getCode()));
        columns.put("current_step_seq", TsColumnValue.of(execution.getCurrentStepSeq()));
        if (execution.getStepLogs() != null) {
            columns.put("step_logs", TsColumnValue.of(execution.getStepLogs()));
        }
        if (execution.getTxPlanJson() != null) {
            columns.put("tx_plan", TsColumnValue.of(execution.getTxPlanJson()));
        }
        if (execution.getOperator() != null) {
            columns.put("operator", TsColumnValue.of(execution.getOperator()));
        }
        if (execution.getTraceId() != null) {
            columns.put("trace_id", TsColumnValue.of(execution.getTraceId()));
        }
        columns.put("lease_owner", TsColumnValue.of(
                execution.getLeaseOwner() == null ? "" : execution.getLeaseOwner()));
        columns.put("lease_until", TsColumnValue.of(execution.getLeaseUntil()));
        columns.put("create_time", TsColumnValue.of(execution.getCreateTime()));
        columns.put("update_time", TsColumnValue.of(execution.getUpdateTime()));
        try {
            template.putRow(TABLE_EXECUTION, new TsRow(sagaPk(execution.getSagaId()), columns),
                    RowCondition.expectNotExist());
            return true;
        } catch (TsConditionCheckException e) {
            // saga_id 已存在：幂等命中（同幂等键重放/恢复重复触发）
            log.info("[saga] 执行记录幂等命中: sagaId={}", execution.getSagaId());
            return false;
        }
    }

    @Override
    public Optional<SagaExecution> find(String sagaId) {
        return template.getRow(TABLE_EXECUTION, sagaPk(sagaId)).map(this::mapExecution);
    }

    @Override
    public void update(SagaExecution execution) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put("status", TsColumnValue.of(execution.getStatus().getCode())));
        updates.add(ColumnUpdate.put("current_step_seq", TsColumnValue.of(execution.getCurrentStepSeq())));
        if (execution.getStepLogs() != null) {
            updates.add(ColumnUpdate.put("step_logs", TsColumnValue.of(execution.getStepLogs())));
        }
        updates.add(ColumnUpdate.put("update_time", TsColumnValue.of(execution.getUpdateTime())));
        if (execution.getLeaseOwner() != null) {
            updates.add(ColumnUpdate.put("lease_owner",
                    TsColumnValue.of(execution.getLeaseOwner())));
            updates.add(ColumnUpdate.put("lease_until",
                    TsColumnValue.of(execution.getLeaseUntil())));
        }
        template.updateRow(TABLE_EXECUTION, sagaPk(execution.getSagaId()), updates);
    }

    @Override
    public List<SagaExecution> scanStuckExecuting(long updateTimeBefore) {
        return scanByStatus(SagaStatus.EXECUTING, updateTimeBefore);
    }

    @Override
    public List<SagaExecution> scanActionsSucceeded(long updateTimeBefore) {
        return scanByStatus(SagaStatus.ACTIONS_SUCCEEDED, updateTimeBefore);
    }

    @Override
    public List<SagaExecution> scanNonTerminal(long updateTimeBefore) {
        List<SagaExecution> result = new ArrayList<>();
        for (TsRow row : template.getRange(TABLE_EXECUTION)) {
            SagaExecution execution = mapExecution(row);
            SagaStatus status = execution.getStatus();
            boolean recoverable = status == SagaStatus.EXECUTING
                    || status == SagaStatus.ACTIONS_SUCCEEDED
                    || status == SagaStatus.COMMIT_PENDING
                    || status == SagaStatus.COMPENSATING;
            if (recoverable && execution.getUpdateTime() <= updateTimeBefore) {
                result.add(execution);
            }
        }
        return result;
    }

    @Override
    public boolean claim(SagaExecution execution, String owner, long leaseUntil,
                         long expectedUpdateTime) {
        try {
            template.updateRow(TABLE_EXECUTION, sagaPk(execution.getSagaId()),
                    List.of(
                            ColumnUpdate.put("lease_owner", TsColumnValue.of(owner)),
                            ColumnUpdate.put("lease_until", TsColumnValue.of(leaseUntil)),
                            ColumnUpdate.put("update_time",
                                    TsColumnValue.of(System.currentTimeMillis()))),
                    RowCondition.expectExistAndAllEq(Map.of(
                            "status", TsColumnValue.of(execution.getStatus().getCode()),
                            "lease_owner", TsColumnValue.of(
                                    execution.getLeaseOwner() == null
                                            ? "" : execution.getLeaseOwner()),
                            "update_time", TsColumnValue.of(expectedUpdateTime))));
            execution.setLeaseOwner(owner);
            execution.setLeaseUntil(leaseUntil);
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    @Override
    public SagaStatus status(String sagaId) {
        return find(sagaId).map(SagaExecution::getStatus).orElse(null);
    }

    @Override
    public boolean releaseLease(String sagaId, String owner, long updateTime) {
        try {
            template.updateRow(TABLE_EXECUTION, sagaPk(sagaId),
                    List.of(
                            ColumnUpdate.put("lease_owner", TsColumnValue.of("")),
                            ColumnUpdate.put("lease_until", TsColumnValue.of(0L)),
                            ColumnUpdate.put("update_time", TsColumnValue.of(updateTime))),
                    RowCondition.expectExistAndColumnEq("lease_owner",
                            TsColumnValue.of(owner)));
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    /** 全表扫描 + 状态/更新时间过滤（V1 无索引，行数百级） */
    private List<SagaExecution> scanByStatus(SagaStatus expected, long updateTimeBefore) {
        List<SagaExecution> result = new ArrayList<>();
        for (TsRow row : template.getRange(TABLE_EXECUTION)) {
            SagaExecution execution = mapExecution(row);
            if (execution.getStatus() == expected
                    && execution.getUpdateTime() <= updateTimeBefore) {
                result.add(execution);
            }
        }
        return result;
    }

    // ==================== saga_dead_letter ====================

    @Override
    public void saveDeadLetter(SagaDeadLetter deadLetter) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("doc_code", TsColumnValue.of(deadLetter.getDocCode()));
        columns.put("doc_uuid", TsColumnValue.of(deadLetter.getDocUuid()));
        columns.put("from_status", TsColumnValue.of(deadLetter.getFromStatus()));
        columns.put("to_status", TsColumnValue.of(deadLetter.getToStatus()));
        columns.put("failed_step_seq", TsColumnValue.of(deadLetter.getFailedStepSeq()));
        columns.put("fail_phase", TsColumnValue.of(deadLetter.getFailPhase() == null
                ? "compensate" : deadLetter.getFailPhase()));
        columns.put("error_reason", TsColumnValue.of(deadLetter.getErrorReason() == null
                ? "" : deadLetter.getErrorReason()));
        if (deadLetter.getCompensateResponses() != null) {
            columns.put("compensate_responses", TsColumnValue.of(deadLetter.getCompensateResponses()));
        }
        columns.put("handle_status", TsColumnValue.of(deadLetter.getHandleStatus()));
        if (deadLetter.getHandler() != null) {
            columns.put("handler", TsColumnValue.of(deadLetter.getHandler()));
        }
        if (deadLetter.getHandleTime() != null) {
            columns.put("handle_time", TsColumnValue.of(deadLetter.getHandleTime()));
        }
        if (deadLetter.getHandleRemark() != null) {
            columns.put("handle_remark", TsColumnValue.of(deadLetter.getHandleRemark()));
        }
        if (deadLetter.getOperator() != null) {
            columns.put("operator", TsColumnValue.of(deadLetter.getOperator()));
        }
        columns.put("create_time", TsColumnValue.of(deadLetter.getCreateTime()));
        template.putRow(TABLE_DEAD_LETTER, new TsRow(sagaPk(deadLetter.getSagaId()), columns));
    }

    @Override
    public List<SagaDeadLetter> listDeadLetters(Long handleStatus, int offset, int limit) {
        List<SagaDeadLetter> all = scanDeadLetters(handleStatus);
        // create_time 降序（最新在前）
        all.sort(Comparator.comparingLong(SagaDeadLetter::getCreateTime).reversed());
        int safeOffset = Math.max(0, offset);
        if (safeOffset >= all.size()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(1, limit), QueryLimit.MAX);
        return all.subList(safeOffset, Math.min(safeOffset + safeLimit, all.size()));
    }

    @Override
    public long countDeadLetters(Long handleStatus) {
        return scanDeadLetters(handleStatus).size();
    }

    @Override
    public Optional<SagaDeadLetter> findDeadLetter(String sagaId) {
        return template.getRow(TABLE_DEAD_LETTER, sagaPk(sagaId)).map(this::mapDeadLetter);
    }

    // ==================== 行映射与工具 ====================

    private List<SagaDeadLetter> scanDeadLetters(Long handleStatus) {
        List<SagaDeadLetter> result = new ArrayList<>();
        for (TsRow row : template.getRange(TABLE_DEAD_LETTER)) {
            SagaDeadLetter letter = mapDeadLetter(row);
            if (handleStatus == null || letter.getHandleStatus() == handleStatus) {
                result.add(letter);
            }
        }
        return result;
    }

    private SagaExecution mapExecution(TsRow row) {
        SagaExecution execution = new SagaExecution();
        execution.setSagaId(String.valueOf(row.getPrimaryKey().get("saga_id")));
        execution.setDocCode(row.getString("doc_code"));
        execution.setDocUuid(row.getString("doc_uuid"));
        execution.setFromStatus(longOf(row, "from_status"));
        execution.setToStatus(longOf(row, "to_status"));
        execution.setExpectedVersion(longOf(row, "expected_version"));
        SagaStatus status = SagaStatus.fromCode(row.getString("status"));
        execution.setStatus(status == null ? SagaStatus.EXECUTING : status);
        execution.setCurrentStepSeq(longOf(row, "current_step_seq"));
        execution.setStepLogs(row.getString("step_logs"));
        execution.setTxPlanJson(row.getString("tx_plan"));
        execution.setOperator(row.getString("operator"));
        execution.setTraceId(row.getString("trace_id"));
        execution.setLeaseOwner(row.getString("lease_owner"));
        execution.setLeaseUntil(longOf(row, "lease_until"));
        execution.setCreateTime(longOf(row, "create_time"));
        execution.setUpdateTime(longOf(row, "update_time"));
        return execution;
    }

    private SagaDeadLetter mapDeadLetter(TsRow row) {
        SagaDeadLetter letter = new SagaDeadLetter();
        letter.setSagaId(String.valueOf(row.getPrimaryKey().get("saga_id")));
        letter.setDocCode(row.getString("doc_code"));
        letter.setDocUuid(row.getString("doc_uuid"));
        letter.setFromStatus(longOf(row, "from_status"));
        letter.setToStatus(longOf(row, "to_status"));
        letter.setFailedStepSeq(longOf(row, "failed_step_seq"));
        letter.setFailPhase(row.getString("fail_phase"));
        letter.setErrorReason(row.getString("error_reason"));
        letter.setCompensateResponses(row.getString("compensate_responses"));
        letter.setHandleStatus(longOf(row, "handle_status"));
        letter.setHandler(row.getString("handler"));
        Long handleTime = row.getLong("handle_time");
        letter.setHandleTime(handleTime);
        letter.setHandleRemark(row.getString("handle_remark"));
        letter.setOperator(row.getString("operator"));
        letter.setCreateTime(longOf(row, "create_time"));
        return letter;
    }

    /** 主键构造：partition_key = saga_{hash4(saga_id)} + saga_id */
    static TsPrimaryKey sagaPk(String sagaId) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.sagaKey(sagaId))
                .add("saga_id", sagaId)
                .build();
    }

    private static long longOf(TsRow row, String column) {
        Long value = row.getLong(column);
        return value == null ? 0L : value;
    }

    /** 死信分页页长上限（与多元索引单页口径一致） */
    static final class QueryLimit {
        static final int MAX = 100;

        private QueryLimit() {
        }
    }
}
