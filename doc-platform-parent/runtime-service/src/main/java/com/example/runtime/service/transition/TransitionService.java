package com.example.runtime.service.transition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.cache.CacheKeyBuilder;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.exception.TsOperationConflictException;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.model.DocDataRecord;
import com.example.runtime.domain.model.DocDefinition;
import com.example.runtime.domain.model.DocStateMachine;
import com.example.runtime.domain.port.DocConfigGateway;
import com.example.runtime.domain.port.DocDataStore;
import com.example.runtime.domain.saga.SagaStatus;
import com.example.runtime.domain.saga.TxPlan;
import com.example.runtime.domain.port.DocTransitionAbortException;
import com.example.runtime.service.AuditService;
import com.example.runtime.service.event.OutboxRelayService;
import com.example.runtime.service.rule.DocRuleChecker;
import com.example.runtime.service.saga.SagaOrchestrator;

/**
 * 单据状态流转服务（八步链路，design/05 §二 / design/08 §3.2）：
 * <ol>
 *   <li>① Redisson 分布式锁 {@code ddp:runtime:lock:doc:{uuid}}：tryLock 3s
 *       失败即 42901（不排队），无 leaseTime → 看门狗自动续期；</li>
 *   <li>② 单据存在性与 current_status 读取（主键 GetRow 强一致）；</li>
 *   <li>③ 状态机合法性校验（全走 runtime 两级缓存）→ 非法 40003；</li>
 *   <li>④ 规则校验（缓存 + doc-common 执行器，失败短路事务）→ 42201 + failed_rules；</li>
 *   <li>④.5 Saga 前置（阶段三，design/05 §2.5 时序裁决）：tx_config 匹配
 *       (from_status, to_status) 即在局部事务<b>外</b>执行事务步骤编排；
 *       冲正成功 → 42202 单据状态不变；冲正失败 → 42202 + 死信人工处置；</li>
 *   <li>⑤ 单表事务收口（OTS 表级作用域硬约束）：事务内仅写 anchor 表 doc_data
 *       主行双列 CAS；历史行（seq=newVersion，expectNotExist 拒覆盖）/
 *       event_outbox/流转审计移至提交后幂等补写；</li>
 *   <li>⑥ OTSRowOperationConflict 退避重试（模板内置 50/150/450ms×3），
 *       OTSConditionCheckFail 不重试直接 40901；</li>
 *   <li>⑦ 幂等：X-Idempotency-Key 由控制器经 IdempotencyService 包裹（锁外占位，
 *       格式约定 {@code {uuid}:{action}:{clientToken}}，design/05 §5.2）；</li>
 *   <li>⑧ 流转审计为提交后幂等补写（audit_log hash4 变体分区键，WriteGuard 豁免；
 *       失败仅 WARN 不阻断，准最终一致，区别于 CRUD 异步旁路，design/03 §2.10）。</li>
 * </ol>
 *
 * <p>红线：Saga 执行 / 规则执行 / Redis 操作 / outbox 即时首发严格在局部事务之外。
 * 事务提交后 outbox 行经 {@link OutboxRelayService#enqueue} 入内存队列即时首发
 * （EV-01），5s 定时扫描兜底。</p>
 *
 * <p>校验拒绝审计（leader 阶段二补项）：③ 40003 / ④ 42201 分支在事务外
 * 尽力异步写审计（operation=validation_reject），失败仅 WARN 不阻断主流程。</p>
 */
@Service
public class TransitionService {

    private static final Logger log = LoggerFactory.getLogger(TransitionService.class);

    /** 分布式锁等待上限（design/05 §2.1：3s 失败即 42901，不排队） */
    private static final long LOCK_WAIT_SECONDS = 3L;

    /** 校验拒绝审计操作类型 */
    private static final String OP_VALIDATION_REJECT = "validation_reject";

    private final RedissonClient redissonClient;
    private final DocConfigGateway configGateway;
    private final DocDataStore docDataStore;
    private final DocRuleChecker ruleChecker;
    private final AuditService auditService;
    private final SagaOrchestrator sagaOrchestrator;
    private final OutboxRelayService outboxRelayService;

    public TransitionService(RedissonClient redissonClient,
                             DocConfigGateway configGateway,
                             DocDataStore docDataStore,
                             DocRuleChecker ruleChecker,
                             AuditService auditService,
                             SagaOrchestrator sagaOrchestrator,
                             OutboxRelayService outboxRelayService) {
        this.redissonClient = redissonClient;
        this.configGateway = configGateway;
        this.docDataStore = docDataStore;
        this.ruleChecker = ruleChecker;
        this.auditService = auditService;
        this.sagaOrchestrator = sagaOrchestrator;
        this.outboxRelayService = outboxRelayService;
    }

    /**
     * 执行状态流转。
     *
     * @param docCode        单据编码
     * @param uuid           单据实例标识
     * @param targetStatus   目标状态
     * @param event          事件名（submit/audit 等，规则时机过滤与审计用）
     * @param operator       操作人（X-User-Id）
     * @param idempotencyKey 幂等键（X-Idempotency-Key，Saga 幂等派生用，可空）
     * @return 成功视图（from_status/to_status/version/history_seq/saga_triggered/saga_id）
     * @throws BizException 42901 锁忙 / 40401 不存在 / 40003 非法流转 /
     *                      42201 规则失败 / 42202 事务步骤失败（已冲正或转死信）/
     *                      40901 条件冲突或退避后仍冲突
     */
    public Map<String, Object> transition(String docCode, String uuid, long targetStatus,
                                          String event, String operator, String idempotencyKey) {
        // 配置读取全部走两级缓存（失效经广播，≤5s）
        DocDefinition definition = configGateway.getDocSchema(docCode);
        if (!definition.hasStatus(targetStatus)) {
            recordRejectAudit(docCode, uuid, operator, ErrorCode.ILLEGAL_TRANSITION.getCode(),
                    "目标状态未在状态字典登记: " + targetStatus);
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION,
                    "目标状态未在状态字典登记: " + targetStatus);
        }

        // ① 分布式锁：tryLock 3s 失败即 42901（不排队）；无 leaseTime → 看门狗续期
        RLock lock = redissonClient.getLock(CacheKeyBuilder.runtimeDocTransitionLock(uuid));
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LOCK_BUSY, "获取流转锁被中断，请重试");
        }
        if (!acquired) {
            throw new BizException(ErrorCode.LOCK_BUSY,
                    "单据正在被并发流转处理，请稍后重试: " + uuid);
        }
        try {
            // ② 存在性与当前状态读取（主键 GetRow，逻辑删行视为不存在）
            DocDataRecord record = docDataStore.find(docCode, uuid)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                            "单据不存在或已删除: " + docCode + "/" + uuid));
            long currentStatus = record.getCurrentStatus();

            // ③ 状态机合法性（runtime 缓存；未配置=空流转表，任何流转 40003）
            DocStateMachine stateMachine = configGateway.getStateMachine(docCode);
            if (!stateMachine.isLegal(currentStatus, targetStatus)) {
                String message = "非法流转: " + definition.stateName(currentStatus) + "("
                        + currentStatus + ") → " + definition.stateName(targetStatus)
                        + "(" + targetStatus + ")";
                recordRejectAudit(docCode, uuid, operator,
                        ErrorCode.ILLEGAL_TRANSITION.getCode(), message);
                throw new BizException(ErrorCode.ILLEGAL_TRANSITION, message);
            }

            // ④ 规则校验（status_rules[target]；失败短路，不进入事务）
            List<Map<String, Object>> failedRules = ruleChecker.checkTransition(
                    docCode, uuid, currentStatus, targetStatus, definition,
                    record.getAttributes(), operator);
            if (!failedRules.isEmpty()) {
                recordRejectAudit(docCode, uuid, operator, ErrorCode.RULE_FAILED.getCode(),
                        "规则校验未通过: failed_rules=" + failedRuleUuids(failedRules));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("failed_rules", failedRules);
                // 规则失败短路：Saga 前置尚未执行，恒 false
                data.put("saga_executed", false);
                throw new BizException(ErrorCode.RULE_FAILED, "规则校验未通过", data);
            }

            // ④.5 Saga 前置（design/05 §2.5 事务前执行，事务外红线）：
            // tx_config 匹配 (from_status, to_status) → 正向按 seq 执行外部步骤，
            // 失败逆序冲正；全部成功才进入 ⑤ 局部事务改单据状态。
            TxPlan plan = configGateway.getTxPlans(docCode).stream()
                    .filter(p -> p.fromStatus() == currentStatus && p.toStatus() == targetStatus)
                    .findFirst().orElse(null);
            String sagaId = null;
            boolean sagaTriggered = false;
            if (plan != null) {
                sagaId = SagaOrchestrator.deriveSagaId(idempotencyKey, uuid, targetStatus);
                try {
                    SagaOrchestrator.SagaResult sagaResult = sagaOrchestrator.execute(
                            sagaId, plan, docCode, uuid, currentStatus, targetStatus, operator);
                    // 幂等重放可能回放 COMPENSATED/DEAD：不得继续提交单据
                    if (sagaResult != null && sagaResult.status() != SagaStatus.COMPLETED) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("saga_id", sagaId);
                        data.put("saga_status", sagaResult.status() == null
                                ? "UNKNOWN" : sagaResult.status().getCode());
                        throw new BizException(ErrorCode.TX_FAILED,
                                "事务步骤此前未成功完成（" + data.get("saga_status")
                                        + "），不可提交单据", data);
                    }
                    sagaTriggered = true;
                } catch (SagaOrchestrator.SagaCompensatedException e) {
                    // 冲正成功：单据状态不变，42202 + saga 证据
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("saga_id", e.getSagaId());
                    data.put("saga_status", "COMPENSATED");
                    throw new BizException(ErrorCode.TX_FAILED,
                            "事务步骤执行失败，已冲正，单据状态不变", data);
                } catch (SagaOrchestrator.SagaDeadException e) {
                    // 冲正失败：转死信人工处置（P-OP-02），单据状态不变
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("saga_id", e.getSagaId());
                    data.put("saga_status", "DEAD");
                    throw new BizException(ErrorCode.TX_FAILED,
                            "事务步骤执行失败且冲正失败，已转死信人工处置", data);
                }
            }

            // ⑤⑥ 局部事务收口 T1-T6（冲突退避由模板内置；条件失败映射 40901）
            DocDataStore.TransitionOutcome outcome;
            try {
                outcome = docDataStore.commitTransition(docCode, uuid, record.getVersion(),
                        currentStatus, targetStatus, operator, event, sagaId);
            } catch (DocTransitionAbortException e) {
                // 事务内发现行缺失/已删（锁内竞态兜底）：外部步骤必须冲正
                compensateAfterCommitFailure(sagaId);
                throw new BizException(ErrorCode.NOT_FOUND, e.getMessage());
            } catch (TsConditionCheckException e) {
                // OTSConditionCheckFail：外部步骤已成功，先冲正再返回 40901
                String suffix = compensateAfterCommitFailure(sagaId);
                throw new BizException(ErrorCode.STATUS_CONFLICT,
                        "单据已被并发修改" + suffix + "，请刷新后重试", e);
            } catch (TsOperationConflictException e) {
                // 退避 50/150/450ms×3 后仍冲突：同样先冲正再按可重试冲突返回
                String suffix = compensateAfterCommitFailure(sagaId);
                throw new BizException(ErrorCode.STATUS_CONFLICT,
                        "存储层并发冲突" + suffix + "，请重试", e);
            }

            // ⑤.5 outbox 即时首发（事务外）：内存队列异步入队，失败由 5s 扫描兜底
            try {
                outboxRelayService.enqueue(PartitionKeys.dataKey(docCode, uuid),
                        outcome.eventId());
            } catch (RuntimeException e) {
                // 入队失败不阻断主流程：扫描兜底（EV-01）保证最终投递
                log.warn("[transition] outbox 即时入队失败，转扫描兜底: eventId={}",
                        outcome.eventId(), e);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uuid", uuid);
            result.put("doc_code", docCode);
            result.put("from_status", currentStatus);
            result.put("to_status", targetStatus);
            result.put("version", outcome.version());
            result.put("history_seq", outcome.historySeq());
            result.put("saga_triggered", sagaTriggered);
            if (sagaId != null) {
                result.put("saga_id", sagaId);
            }
            log.info("[transition] 流转成功: {}/{} {}→{}, version={}, seq={}, saga_triggered={}",
                    docCode, uuid, currentStatus, targetStatus,
                    outcome.version(), outcome.historySeq(), sagaTriggered);
            return result;
        } finally {
            // 仅持有者解锁（看门狗续期下防误释放他人锁）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 本地提交失败时冲正已成功的外部步骤（TX-03 收口）。
     * 冲正失败转死信并直接抛出 42202，避免用户误以为单据已回滚。
     *
     * @param sagaId Saga 实例 ID（无外部步骤时为 null）
     * @return 面向用户的冲突提示后缀（无外部步骤为空；冲正结果未知时明确提示人工核对）
     */
    private String compensateAfterCommitFailure(String sagaId) {
        if (sagaId == null) {
            return "";
        }
        SagaStatus status;
        try {
            status = sagaOrchestrator.compensateAfterCommitFailure(sagaId);
        } catch (RuntimeException e) {
            log.error("[transition] 提交失败后的冲正异常，需人工核对: sagaId={}", sagaId, e);
            status = SagaStatus.DEAD;
        }
        if (status == SagaStatus.DEAD) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("saga_id", sagaId);
            data.put("saga_status", "DEAD");
            throw new BizException(ErrorCode.TX_FAILED,
                    "单据提交冲突，外部步骤冲正失败，已转死信人工处置", data);
        }
        if (status == SagaStatus.COMPENSATED) {
            log.warn("[transition] 本地提交失败，外部步骤已冲正: sagaId={}", sagaId);
            return "，外部步骤已冲正";
        }
        log.error("[transition] 本地提交失败，外部步骤冲正状态未知: sagaId={}, status={}",
                sagaId, status);
        return "，外部步骤冲正状态未知，请人工核对";
    }

    // ==================== 校验拒绝审计（事务外尽力而为） ====================

    /**
     * 校验拒绝审计（40003/42201）：事务外异步旁路，best-effort——
     * AuditService 内部吞写失败仅 WARN，不影响业务异常传播。
     */
    private void recordRejectAudit(String docCode, String uuid, String operator,
                                   int code, String reason) {
        auditService.recordAsync(new AuditEntry(UUID.randomUUID().toString(),
                OP_VALIDATION_REJECT, "doc", docCode, uuid,
                operator == null ? "anonymous" : operator, System.currentTimeMillis(),
                null, rejectAfterJson(code, reason), null, org.slf4j.MDC.get("traceId")));
    }

    /** 拒绝审计 after_value JSON 化（RD-12 before/after 快照，JSON Diff 可用） */
    private static String rejectAfterJson(int code, String reason) {
        String escaped = reason == null ? "" : reason
                .replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"code\":" + code + ",\"reason\":\"" + escaped + "\"}";
    }

    /** 失败规则 uuid 列表（审计明细压缩） */
    private String failedRuleUuids(List<Map<String, Object>> failedRules) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> rule : failedRules) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(rule.get("rule_uuid"));
        }
        return sb.toString();
    }
}

