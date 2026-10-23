package com.example.runtime.service.saga;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.saga.SagaDeadLetter;
import com.example.runtime.domain.saga.SagaExecution;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.SagaStore;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.service.saga.SagaStepHttpClient.StepInvocationException;
import com.example.runtime.service.saga.SagaStepHttpClient.StepResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Saga 编排器（TX-01/02/03，design/05 §2.5 / design/08 §3.3）。
 *
 * <p><b>时序口径（design/05 §2.5）</b>：正向步骤在流转局部事务<b>之前</b>执行——
 * 全部成功才提交事务（COMPLETED）；任一步失败逆序冲正（COMPENSATED，单据状态
 * 保持原值，调用方返回 42202）；冲正失败落死信（DEAD，ERROR 告警）。任务清单 3
 * 字面「事务提交后启动」无法满足验收「APS 失败单据状态不变」，按设计文档时序
 * 裁决（报告说明）。</p>
 *
 * <p>幂等：saga_id 由流转幂等键派生，saga_execution EXPECT_NOT_EXIST 创建即幂等；
 * 崩溃恢复/死信重试经执行记录内 tx_plan 快照自持，不依赖 tx_config 现状。</p>
 */
@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    /** 步骤日志阶段：正向执行 */
    static final String PHASE_ACTION = "action";

    /** 步骤日志阶段：冲正 */
    static final String PHASE_COMPENSATE = "compensate";

    private final SagaStore sagaStore;
    private final SagaStepHttpClient stepClient;
    private final SagaProperties properties;
    private final ObjectMapper objectMapper;

    /** Saga 指标（design/11 §4.2：ddp_saga_execution_total / ddp_saga_dead_letter_total） */
    private Counter completedCounter;
    private Counter compensatedCounter;
    private Counter deadCounter;
    private Counter deadLetterCounter;

    public SagaOrchestrator(SagaStore sagaStore, SagaStepHttpClient stepClient,
                            SagaProperties properties, ObjectMapper objectMapper) {
        this.sagaStore = sagaStore;
        this.stepClient = stepClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 编排结果（saga_id + 终态） */
    public record SagaResult(String sagaId, SagaStatus status) {
    }

    /**
     * 绑定 Saga 指标：{@code ddp_saga_execution_total{result=completed|compensated|dead}}
     * 与 {@code ddp_saga_dead_letter_total}（design/11 §4.2 / SG-01）。
     * Setter 注入（required=false）：测试手工构造不打点。
     *
     * @param registry Micrometer 注册表
     */
    @Autowired(required = false)
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        this.completedCounter = Counter.builder("ddp_saga_execution_total")
                .tag("result", "completed").register(registry);
        this.compensatedCounter = Counter.builder("ddp_saga_execution_total")
                .tag("result", "compensated").register(registry);
        this.deadCounter = Counter.builder("ddp_saga_execution_total")
                .tag("result", "dead").register(registry);
        this.deadLetterCounter = Counter.builder("ddp_saga_dead_letter_total")
                .register(registry);
    }

    /** 计数器空安全自增（未绑定 metrics 时 NOOP） */
    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 流转正向失败且冲正完成（单据状态不变，调用方映射 42202）。
     */
    public static class SagaCompensatedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String sagaId;

        public SagaCompensatedException(String sagaId, String reason) {
            super(reason);
            this.sagaId = sagaId;
        }

        public String getSagaId() {
            return sagaId;
        }
    }

    /**
     * 编排失败且冲正失败（已落死信，ERROR 告警；单据状态同样不变，调用方映射 42202）。
     */
    public static class SagaDeadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String sagaId;

        public SagaDeadException(String sagaId, String reason) {
            super(reason);
            this.sagaId = sagaId;
        }

        public String getSagaId() {
            return sagaId;
        }
    }

    // ==================== 正向编排入口 ====================

    /**
     * 执行 Saga（幂等）：创建执行记录 → 正向按 seq 执行 → 失败逆序冲正。
     *
     * @param plan           事务计划（tx_config 按流转匹配）
     * @param docCode        单据编码
     * @param uuid           单据实例
     * @param expectedVersion 规则/Saga 执行前读取的单据版本（恢复提交快照）
     * @param fromStatus     起始状态
     * @param toStatus       目标状态
     * @param operator       操作人
     * @param idempotencyKey 流转幂等键（X-Idempotency-Key，可空→退化 uuid+toStatus）
     * @return 编排结果（ACTIONS_SUCCEEDED 才可继续提交流转事务）
     * @throws SagaCompensatedException 正向失败且冲正完成
     * @throws SagaDeadException        冲正失败（已落死信）
     */
    public SagaResult execute(TxPlan plan, String docCode, String uuid, long expectedVersion,
                              long fromStatus, long toStatus, String operator,
                              String idempotencyKey) {
        return execute(deriveSagaId(idempotencyKey, uuid, toStatus), plan, docCode, uuid,
                expectedVersion, fromStatus, toStatus, operator);
    }

    /**
     * Execute saga with an externally pre-derived sagaId
     * (avoids random-tail divergence caused by double derivation without idempotency key).
     *
     * @param sagaId pre-derived saga instance id
     * @param expectedVersion 规则/Saga 执行前读取的单据版本
     */
    public SagaResult execute(String sagaId, TxPlan plan, String docCode, String uuid,
                              long expectedVersion, long fromStatus, long toStatus,
                              String operator) {
        long now = System.currentTimeMillis();

        SagaExecution execution = new SagaExecution();
        execution.setSagaId(sagaId);
        execution.setDocCode(docCode);
        execution.setDocUuid(uuid);
        execution.setFromStatus(fromStatus);
        execution.setToStatus(toStatus);
        execution.setExpectedVersion(expectedVersion);
        execution.setStatus(SagaStatus.EXECUTING);
        execution.setCurrentStepSeq(0L);
        execution.setStepLogs("[]");
        execution.setTxPlanJson(toJson(plan));
        execution.setOperator(operator);
        execution.setTraceId(SagaProperties.currentTraceId());
        execution.setCreateTime(now);
        execution.setUpdateTime(now);

        if (!sagaStore.createIfAbsent(execution)) {
            // 幂等命中：同幂等键重放，直接回放既有终态
            SagaExecution existing = sagaStore.find(sagaId).orElse(execution);
            log.info("[saga] 幂等命中回放: sagaId={}, status={}", sagaId, existing.getStatus());
            return new SagaResult(sagaId, existing.getStatus());
        }

        List<Map<String, Object>> logs = new ArrayList<>();
        long startMs = now;
        runForward(execution, plan, logs, 1, startMs);
        return new SagaResult(sagaId, execution.getStatus());
    }

    /** saga_id 派生：幂等键优先（重放同 saga），缺省退化 uuid+toStatus+随机尾 */
    public static String deriveSagaId(String idempotencyKey, String uuid, long toStatus) {
        String seed = (idempotencyKey == null || idempotencyKey.isBlank())
                ? uuid + ":" + toStatus + ":" + java.util.UUID.randomUUID()
                : idempotencyKey;
        return "saga_" + PartitionKeys.hash(seed, 12);
    }

    // ==================== 正向执行 ====================

    /** 正向按 seq 执行（预算检查 + 失败转冲正），成功置 COMPLETED */
    private void runForward(SagaExecution execution, TxPlan plan,
                            List<Map<String, Object>> logs, int fromSeq, long startMs) {
        List<TxPlan.TxStep> steps = plan.steps();
        boolean allOk = true;
        String failReason = null;
        long failedSeq = 0L;
        for (TxPlan.TxStep step : steps) {
            if (step.seq() < fromSeq) {
                continue;
            }
            if (System.currentTimeMillis() - startMs > properties.getTotalBudgetMs()) {
                allOk = false;
                failReason = "Saga 总预算耗尽（>" + properties.getTotalBudgetMs() + "ms）: step="
                        + step.name();
                failedSeq = step.seq();
                appendLog(logs, step.seq(), PHASE_ACTION, false, failReason);
                break;
            }
            execution.setCurrentStepSeq(step.seq());
            execution.setStepLogs(toJson(logs));
            execution.setUpdateTime(System.currentTimeMillis());
            sagaStore.update(execution);
            try {
                StepResponse response = stepClient.post(step.actionUrl(),
                        stepPayload(execution, step, PHASE_ACTION), step, execution.getSagaId());
                if (response.failed()) {
                    allOk = false;
                    failReason = "步骤正向执行被拒绝: step=" + step.name()
                            + ", http=" + response.statusCode();
                    failedSeq = step.seq();
                    appendLog(logs, step.seq(), PHASE_ACTION, false, failReason);
                    break;
                }
                appendLog(logs, step.seq(), PHASE_ACTION, true, null);
            } catch (StepInvocationException e) {
                allOk = false;
                failReason = e.getMessage();
                failedSeq = step.seq();
                appendLog(logs, step.seq(), PHASE_ACTION, false, failReason);
                break;
            }
        }
        if (allOk) {
            // 外部步骤已成功，但本地单据尚未提交：停在 ACTIONS_SUCCEEDED，
            // 由调用方提交成功后再 markCommitted；崩溃窗口由恢复任务收口（TX-03）。
            execution.setStatus(SagaStatus.ACTIONS_SUCCEEDED);
            execution.setStepLogs(toJson(logs));
            execution.setUpdateTime(System.currentTimeMillis());
            sagaStore.update(execution);
            log.info("[saga] 外部步骤全部成功，待本地提交: sagaId={}, steps={}",
                    execution.getSagaId(), steps.size());
            return;
        }
        // 正向失败：逆序冲正已成功步骤（seq < failedSeq 的全部成功步 + 失败步本身不冲正）
        compensate(execution, plan, logs, failedSeq, failReason, startMs);
    }

    // ==================== 逆序冲正 ====================

    /**
     * 逆序冲正（seq 降序，仅冲正「正向已成功」的步骤；compensateUrl 为空的步骤跳过）。
     * 全部成功 → COMPENSATED（抛 SagaCompensatedException）；
     * 任一失败 → DEAD + 死信（抛 SagaDeadException）。
     */
    private void compensate(SagaExecution execution, TxPlan plan,
                            List<Map<String, Object>> logs, long failedSeq,
                            String failReason, long startMs) {
        execution.setStatus(SagaStatus.COMPENSATING);
        execution.setStepLogs(toJson(logs));
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);
        log.warn("[saga] 正向失败转冲正: sagaId={}, failedSeq={}, reason={}",
                execution.getSagaId(), failedSeq, failReason);

        List<TxPlan.TxStep> reversed = new ArrayList<>(plan.steps());
        reversed.sort(Comparator.comparingInt(TxPlan.TxStep::seq).reversed());
        List<Map<String, Object>> compensateResponses = new ArrayList<>();
        for (TxPlan.TxStep step : reversed) {
            if (step.seq() >= failedSeq) {
                // 失败步及未执行步无需冲正
                continue;
            }
            if (!actionSucceeded(logs, step.seq())) {
                continue;
            }
            if (step.compensateUrl() == null || step.compensateUrl().isBlank()) {
                appendLog(logs, step.seq(), PHASE_COMPENSATE, true, "no-compensate-endpoint");
                continue;
            }
            if (System.currentTimeMillis() - startMs > properties.getTotalBudgetMs()) {
                deadLetter(execution, plan, logs, failedSeq, failReason,
                        "冲正阶段预算耗尽: step=" + step.name(), compensateResponses);
                return;
            }
            try {
                StepResponse response = stepClient.post(step.compensateUrl(),
                        stepPayload(execution, step, PHASE_COMPENSATE), step,
                        execution.getSagaId());
                if (response.failed()) {
                    appendLog(logs, step.seq(), PHASE_COMPENSATE, false,
                            "http=" + response.statusCode());
                    deadLetter(execution, plan, logs, failedSeq, failReason,
                            "冲正被拒绝: step=" + step.name() + ", http=" + response.statusCode(),
                            compensateResponses);
                    return;
                }
                appendLog(logs, step.seq(), PHASE_COMPENSATE, true, null);
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("seq", step.seq());
                snapshot.put("name", step.name());
                snapshot.put("body", response.body());
                compensateResponses.add(snapshot);
            } catch (StepInvocationException e) {
                appendLog(logs, step.seq(), PHASE_COMPENSATE, false, e.getMessage());
                deadLetter(execution, plan, logs, failedSeq, failReason,
                        "冲正调用失败: step=" + step.name() + ", cause=" + e.getMessage(),
                        compensateResponses);
                return;
            }
        }
        execution.setStatus(SagaStatus.COMPENSATED);
        execution.setStepLogs(toJson(logs));
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);
        increment(compensatedCounter);
        log.warn("[saga] 冲正完成（单据状态保持原值）: sagaId={}, reason={}",
                execution.getSagaId(), failReason);
        throw new SagaCompensatedException(execution.getSagaId(), failReason);
    }

    /** 冲正失败 → DEAD 终态 + 死信落表 + ERROR 告警日志 */
    private void deadLetter(SagaExecution execution, TxPlan plan,
                            List<Map<String, Object>> logs, long failedSeq,
                            String failReason, String compensateError,
                            List<Map<String, Object>> compensateResponses) {
        execution.setStatus(SagaStatus.DEAD);
        execution.setStepLogs(toJson(logs));
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);

        long now = System.currentTimeMillis();
        SagaDeadLetter letter = new SagaDeadLetter();
        letter.setSagaId(execution.getSagaId());
        letter.setDocCode(execution.getDocCode());
        letter.setDocUuid(execution.getDocUuid());
        letter.setFromStatus(execution.getFromStatus());
        letter.setToStatus(execution.getToStatus());
        letter.setFailedStepSeq(failedSeq);
        letter.setFailPhase(PHASE_COMPENSATE);
        letter.setErrorReason(truncate(compensateError + " | forward=" + failReason, 500));
        letter.setCompensateResponses(toJson(compensateResponses));
        letter.setHandleStatus(0L);
        letter.setOperator(execution.getOperator());
        letter.setCreateTime(now);
        sagaStore.saveDeadLetter(letter);
        increment(deadCounter);
        increment(deadLetterCounter);
        // ERROR 告警位（监控按日志级别订阅，design/08 §3.3）
        log.error("[saga][ALERT] 冲正失败转死信，需人工处置: sagaId={}, doc={}/{}, failedSeq={}, reason={}",
                execution.getSagaId(), execution.getDocCode(), execution.getDocUuid(),
                failedSeq, compensateError);
        throw new SagaDeadException(execution.getSagaId(), compensateError);
    }

    /**
     * 本地单据提交成功后的收口：ACTIONS_SUCCEEDED → COMPLETED。
     * 若更新失败，恢复任务会依据单据已到达目标状态补收口。
     *
     * @param sagaId Saga 实例 ID
     * @return 收口后的状态；记录缺失返回 null
     */
    public SagaStatus markCommitted(String sagaId) {
        if (sagaId == null || sagaId.isBlank()) {
            return null;
        }
        SagaExecution execution = sagaStore.find(sagaId).orElse(null);
        if (execution == null) {
            log.warn("[saga] 标记完成时执行记录缺失: sagaId={}", sagaId);
            return null;
        }
        if (execution.getStatus() == SagaStatus.COMPLETED) {
            return SagaStatus.COMPLETED;
        }
        if (execution.getStatus() != SagaStatus.ACTIONS_SUCCEEDED) {
            log.warn("[saga] 非待提交状态，忽略标记完成: sagaId={}, status={}",
                    sagaId, execution.getStatus());
            return execution.getStatus();
        }
        execution.setStatus(SagaStatus.COMPLETED);
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);
        increment(completedCounter);
        log.info("[saga] 本地提交成功，Saga 完成: sagaId={}", sagaId);
        return SagaStatus.COMPLETED;
    }

    /**
     * 本地单据提交失败后的同步冲正（TX-03 收口）：外部步骤已全部成功，
     * 但流转事务因版本/状态冲突或单据删除未能提交。逆序冲正所有已成功步骤，
     * 使外部副作用与本地状态保持一致；冲正失败转死信。
     *
     * @param sagaId Saga 实例 ID
     * @return 冲正后的终态（COMPENSATED/DEAD）；记录缺失或非待提交状态时返回既有状态
     */
    public SagaStatus compensateAfterCommitFailure(String sagaId) {
        if (sagaId == null || sagaId.isBlank()) {
            return null;
        }
        SagaExecution execution = sagaStore.find(sagaId).orElse(null);
        if (execution == null) {
            log.warn("[saga] 提交失败冲正时执行记录缺失: sagaId={}", sagaId);
            return null;
        }
        if (execution.getStatus() != SagaStatus.ACTIONS_SUCCEEDED
                && execution.getStatus() != SagaStatus.COMPLETED) {
            // 已完成冲正/死信，或仍在执行（由恢复任务接手），不重复冲正
            return execution.getStatus();
        }
        TxPlan plan = parsePlan(execution.getTxPlanJson());
        if (plan == null || plan.steps().isEmpty()) {
            markDeadManual(execution, "本地提交失败且计划快照缺失，无法冲正");
            return SagaStatus.DEAD;
        }
        List<Map<String, Object>> logs = parseLogs(execution.getStepLogs());
        long failedSeq = plan.steps().stream()
                .mapToLong(TxPlan.TxStep::seq).max().orElse(0L) + 1;
        try {
            compensate(execution, plan, logs, failedSeq,
                    "本地单据提交失败，执行外部步骤冲正", System.currentTimeMillis());
        } catch (SagaCompensatedException e) {
            return SagaStatus.COMPENSATED;
        } catch (SagaDeadException e) {
            return SagaStatus.DEAD;
        }
        return execution.getStatus();
    }

    // ==================== 崩溃恢复 ====================

    /**
     * 续推滞留 EXECUTING 记录（定时任务入口）：从最后成功步之后恢复正向；
     * 预算已耗尽则转冲正。未知中间态按「已成功步」保守推进（幂等步骤可重放）。
     */
    public void resumeStuck(SagaExecution execution) {
        TxPlan plan = parsePlan(execution.getTxPlanJson());
        if (plan == null || plan.steps().isEmpty()) {
            log.warn("[saga-recovery] 计划快照缺失，转人工: sagaId={}", execution.getSagaId());
            markDeadManual(execution, "计划快照缺失，崩溃恢复无法续推");
            return;
        }
        List<Map<String, Object>> logs = parseLogs(execution.getStepLogs());
        long lastSuccess = 0L;
        for (Map<String, Object> logEntry : logs) {
            if (PHASE_ACTION.equals(logEntry.get("phase"))
                    && Boolean.TRUE.equals(logEntry.get("result"))) {
                long seq = asLong(logEntry.get("seq"));
                if (seq > lastSuccess) {
                    lastSuccess = seq;
                }
            }
        }
        long startMs = execution.getCreateTime();
        if (System.currentTimeMillis() - startMs > properties.getTotalBudgetMs()) {
            log.warn("[saga-recovery] 预算已耗尽，转冲正: sagaId={}", execution.getSagaId());
            long maxSeq = plan.steps().stream().mapToInt(TxPlan.TxStep::seq).max().orElse(0);
            try {
                compensate(execution, plan, logs, maxSeq + 1, "崩溃恢复：预算耗尽转冲正", startMs);
            } catch (SagaCompensatedException | SagaDeadException expected) {
                // 终态已在 compensate/deadLetter 内落库
            }
            return;
        }
        log.info("[saga-recovery] 续推滞留 Saga: sagaId={}, resumeFromSeq={}",
                execution.getSagaId(), lastSuccess + 1);
        try {
            runForward(execution, plan, logs, (int) (lastSuccess + 1), startMs);
        } catch (SagaCompensatedException | SagaDeadException expected) {
            // 终态已落库
        }
    }

    // ==================== 死信重试 ====================

    /**
     * 死信重试（补完冲正）：按执行记录快照对「正向已成功步」再次逆序冲正；
     * 成功 → COMPENSATED + 死信 handle_status=1（已重试）。
     *
     * @param letter  死信记录
     * @param handler 处置人
     */
    public void retryDeadLetter(SagaDeadLetter letter, String handler) {
        SagaExecution execution = sagaStore.find(letter.getSagaId())
                .orElseThrow(() -> new IllegalStateException("死信对应执行记录缺失: " + letter.getSagaId()));
        TxPlan plan = parsePlan(execution.getTxPlanJson());
        if (plan == null) {
            throw new IllegalStateException("执行记录计划快照缺失，无法重试: " + letter.getSagaId());
        }
        List<Map<String, Object>> logs = parseLogs(execution.getStepLogs());
        List<TxPlan.TxStep> reversed = new ArrayList<>(plan.steps());
        reversed.sort(Comparator.comparingInt(TxPlan.TxStep::seq).reversed());
        List<Map<String, Object>> compensateResponses = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (TxPlan.TxStep step : reversed) {
            if (!actionSucceeded(logs, step.seq())) {
                continue;
            }
            if (step.compensateUrl() == null || step.compensateUrl().isBlank()) {
                continue;
            }
            if (compensateSucceeded(logs, step.seq())) {
                continue;
            }
            try {
                StepResponse response = stepClient.post(step.compensateUrl(),
                        stepPayload(execution, step, PHASE_COMPENSATE), step,
                        execution.getSagaId());
                if (response.failed()) {
                    appendLog(logs, step.seq(), PHASE_COMPENSATE, false,
                            "http=" + response.statusCode());
                    persistRetryFailure(execution, letter, logs, handler,
                            "重试冲正被拒绝: step=" + step.name() + ", http=" + response.statusCode());
                    return;
                }
                appendLog(logs, step.seq(), PHASE_COMPENSATE, true, null);
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("seq", step.seq());
                snapshot.put("name", step.name());
                snapshot.put("body", response.body());
                compensateResponses.add(snapshot);
            } catch (StepInvocationException e) {
                appendLog(logs, step.seq(), PHASE_COMPENSATE, false, e.getMessage());
                persistRetryFailure(execution, letter, logs, handler,
                        "重试冲正调用失败: step=" + step.name() + ", cause=" + e.getMessage());
                return;
            }
        }
        execution.setStatus(SagaStatus.COMPENSATED);
        execution.setStepLogs(toJson(logs));
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);

        letter.setHandleStatus(1L);
        letter.setHandler(handler);
        letter.setHandleTime(now);
        letter.setHandleRemark("retry: 补完冲正成功");
        letter.setCompensateResponses(toJson(compensateResponses));
        sagaStore.saveDeadLetter(letter);
        log.info("[saga] 死信重试成功（补完冲正）: sagaId={}, handler={}", letter.getSagaId(), handler);
    }

    /** 死信重试仍失败：执行记录保持 DEAD，死信追加最新失败原因（handle_status 保持 0） */
    private void persistRetryFailure(SagaExecution execution, SagaDeadLetter letter,
                                     List<Map<String, Object>> logs, String handler, String reason) {
        execution.setStepLogs(toJson(logs));
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);
        letter.setErrorReason(truncate(reason, 500));
        letter.setHandler(handler);
        letter.setHandleTime(System.currentTimeMillis());
        sagaStore.saveDeadLetter(letter);
        log.error("[saga][ALERT] 死信重试仍失败: sagaId={}, reason={}", letter.getSagaId(), reason);
        throw new SagaDeadException(letter.getSagaId(), reason);
    }

    /** 计划快照缺失时的兜底 DEAD 终态（恢复路径用） */
    private void markDeadManual(SagaExecution execution, String reason) {
        execution.setStatus(SagaStatus.DEAD);
        execution.setUpdateTime(System.currentTimeMillis());
        sagaStore.update(execution);
        long now = System.currentTimeMillis();
        SagaDeadLetter letter = new SagaDeadLetter();
        letter.setSagaId(execution.getSagaId());
        letter.setDocCode(execution.getDocCode());
        letter.setDocUuid(execution.getDocUuid());
        letter.setFromStatus(execution.getFromStatus());
        letter.setToStatus(execution.getToStatus());
        letter.setFailedStepSeq(execution.getCurrentStepSeq());
        letter.setFailPhase(PHASE_ACTION);
        letter.setErrorReason(reason);
        letter.setHandleStatus(0L);
        letter.setOperator(execution.getOperator());
        letter.setCreateTime(now);
        sagaStore.saveDeadLetter(letter);
        increment(deadCounter);
        increment(deadLetterCounter);
        log.error("[saga][ALERT] Saga 转死信（无法续推）: sagaId={}, reason={}",
                execution.getSagaId(), reason);
    }

    // ==================== 工具 ====================

    /** 步骤出站请求体（自包含：saga/doc/step 上下文） */
    private String stepPayload(SagaExecution execution, TxPlan.TxStep step, String phase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("saga_id", execution.getSagaId());
        payload.put("doc_code", execution.getDocCode());
        payload.put("doc_uuid", execution.getDocUuid());
        payload.put("from_status", execution.getFromStatus());
        payload.put("to_status", execution.getToStatus());
        payload.put("step_seq", step.seq());
        payload.put("step_name", step.name());
        payload.put("phase", phase);
        payload.put("operator", execution.getOperator() == null ? "anonymous" : execution.getOperator());
        return toJson(payload);
    }

    private void appendLog(List<Map<String, Object>> logs, long seq, String phase,
                           boolean success, String error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", seq);
        entry.put("phase", phase);
        entry.put("result", success);
        entry.put("ts", System.currentTimeMillis());
        if (error != null) {
            entry.put("error", truncate(error, 300));
        }
        logs.add(entry);
    }

    private boolean actionSucceeded(List<Map<String, Object>> logs, long seq) {
        for (Map<String, Object> entry : logs) {
            if (PHASE_ACTION.equals(entry.get("phase")) && asLong(entry.get("seq")) == seq
                    && Boolean.TRUE.equals(entry.get("result"))) {
                return true;
            }
        }
        return false;
    }

    private boolean compensateSucceeded(List<Map<String, Object>> logs, long seq) {
        for (Map<String, Object> entry : logs) {
            if (PHASE_COMPENSATE.equals(entry.get("phase")) && asLong(entry.get("seq")) == seq
                    && Boolean.TRUE.equals(entry.get("result"))) {
                return true;
            }
        }
        return false;
    }

    TxPlan parsePlan(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TxPlan.class);
        } catch (JsonProcessingException e) {
            log.warn("[saga] 计划快照反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseLogs(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> logs = objectMapper.readValue(json, List.class);
            return new ArrayList<>(logs);
        } catch (JsonProcessingException e) {
            log.warn("[saga] 步骤日志反序列化失败，按空日志处理: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Saga 日志序列化失败", e);
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
