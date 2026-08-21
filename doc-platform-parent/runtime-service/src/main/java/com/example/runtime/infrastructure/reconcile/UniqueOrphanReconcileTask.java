package com.example.runtime.infrastructure.reconcile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.util.PartitionKeys;
import com.example.runtime.domain.port.EntityDataRepository;
import com.example.runtime.domain.port.UniqueIndexStore;

/**
 * unique_index 孤儿行对账任务（design/03 §5.6 全量扫描 + 自动对账，
 * leader 阶段二补项裁决）。
 *
 * <p>孤儿定义：unique_index 占位行的 owner_uuid 指向的主行物理缺失
 * （新增占位后主行写入进程崩溃且回滚未执行）。对账策略（只删不增，
 * 安全幂等）：</p>
 * <ul>
 *   <li>全量扫描 unique_index（GetRange 续翻）→ 属主主行反查
 *       （entity_data 优先，doc_data 兜底，两者同 {@code {code}_{hash4(uuid)}} 分区键模式）；</li>
 *   <li>主行物理缺失且占位持续 &gt; 10 分钟（{@link #ORPHAN_FRESH_MILLIS}）→ 释放；
 *       新鲜窗口内跳过（容忍事务在途）；</li>
 *   <li>主行逻辑删（entity status∉{1,100} / doc deleted=1）且持续 &gt; 1 小时
 *       （{@link #DELETED_GRACE_MILLIS}，按主行 update_time 计）→ 释放；</li>
 *   <li>每次释放同步写对账审计（audit_log 同目标分区键变体，operation=reconcile）。</li>
 * </ul>
 *
 * <p>运行控制：配置开关 {@code runtime.reconcile.unique-orphan.enabled}
 * 默认 false（仅控定时通道）；定时 5 分钟一轮；手动触发不受开关限制；
 * {@link AtomicBoolean} 并发防护——已在执行时手动触发返回 40901、定时轮跳过。</p>
 */
@Component
public class UniqueOrphanReconcileTask {

    static final String TABLE_UNIQUE_INDEX = "unique_index";
    static final String TABLE_ENTITY_DATA = "entity_data";
    static final String TABLE_DOC_DATA = "doc_data";
    static final String TABLE_AUDIT_LOG = "audit_log";

    /** 孤儿新鲜期阈值：主行物理缺失超过该时长才判定孤儿（容忍事务在途） */
    static final long ORPHAN_FRESH_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** 逻辑删宽限阈值：主行逻辑删超过该时长后释放唯一占位 */
    static final long DELETED_GRACE_MILLIS = TimeUnit.HOURS.toMillis(1);

    private static final Logger log = LoggerFactory.getLogger(UniqueOrphanReconcileTask.class);

    private final UniqueIndexStore uniqueIndexStore;
    private final EntityDataRepository dataRepository;
    private final TablestoreTemplate template;
    private final boolean scheduledEnabled;

    /** 并发防护：同一时刻仅一轮对账（手动触发冲突 40901，定时轮跳过） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UniqueOrphanReconcileTask(UniqueIndexStore uniqueIndexStore,
                                     EntityDataRepository dataRepository,
                                     TablestoreTemplate template,
                                     @Value("${runtime.reconcile.unique-orphan.enabled:false}") boolean scheduledEnabled) {
        this.uniqueIndexStore = uniqueIndexStore;
        this.dataRepository = dataRepository;
        this.template = template;
        this.scheduledEnabled = scheduledEnabled;
    }

    /**
     * 判定指定唯一值占位是否为孤儿行（属主主行物理缺失，单点判定）。
     *
     * @param targetCode 实体/单据编码
     * @param attrCode   unique 属性编码
     * @param value      归一化属性值
     * @return true=孤儿行（可安全释放）；false=有效占位或无占位
     */
    public boolean isOrphan(String targetCode, String attrCode, String value) {
        return uniqueIndexStore.findOwner(targetCode, attrCode, value)
                .map(owner -> dataRepository.findByKey(targetCode, owner).isEmpty())
                .orElse(false);
    }

    /**
     * 定时对账入口（5 分钟一轮；配置开关默认 false 时跳过）。
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void scheduledReconcile() {
        if (!scheduledEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[reconcile] 上一轮 unique_index 对账仍在执行，本轮跳过");
            return;
        }
        try {
            doReconcile();
        } finally {
            running.set(false);
        }
    }

    /**
     * 手动触发对账（不受配置开关限制；并发执行时抛 40901）。
     *
     * @return 对账摘要 {scanned, released, skipped_fresh, skipped_active, errors}
     */
    public Map<String, Object> triggerManual() {
        if (!running.compareAndSet(false, true)) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "对账任务正在执行中，请稍后重试");
        }
        try {
            return doReconcile();
        } finally {
            running.set(false);
        }
    }

    // ==================== 对账主流程 ====================

    /** 全量扫描 + 孤儿判定 + 释放 + 同分区审计（只删不增，幂等安全） */
    private Map<String, Object> doReconcile() {
        long scanned = 0;
        long released = 0;
        long skippedFresh = 0;
        long skippedActive = 0;
        long errors = 0;
        long now = System.currentTimeMillis();
        for (TsRow row : template.getRange(TABLE_UNIQUE_INDEX)) {
            scanned++;
            try {
                ReconcileDecision decision = decide(row, now);
                switch (decision) {
                    case RELEASE -> {
                        release(row);
                        released++;
                    }
                    case SKIP_FRESH -> skippedFresh++;
                    case SKIP_ACTIVE -> skippedActive++;
                    default -> errors++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.warn("[reconcile] 单行对账异常，跳过: partitionKey={}",
                        row.getPrimaryKey().get("partition_key"), e);
            }
        }
        log.info("[reconcile] unique_index 对账完成: scanned={}, released={}, skippedFresh={}, skippedActive={}, errors={}",
                scanned, released, skippedFresh, skippedActive, errors);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scanned", scanned);
        summary.put("released", released);
        summary.put("skipped_fresh", skippedFresh);
        summary.put("skipped_active", skippedActive);
        summary.put("errors", errors);
        return summary;
    }

    /** 单行对账判定 */
    private ReconcileDecision decide(TsRow row, long now) {
        String partitionKey = String.valueOf(row.getPrimaryKey().get("partition_key"));
        String owner = row.getString("owner_uuid");
        Long createTime = row.getLong("create_time");
        String[] codes = resolveTargetAndAttr(row, partitionKey);
        if (codes == null || owner == null || owner.isBlank()) {
            return ReconcileDecision.ERROR;
        }
        String targetCode = codes[0];
        // 属主主行反查：entity_data 优先，doc_data 兜底（同分区键模式）
        Optional<TsRow> entityRow = template.getRow(TABLE_ENTITY_DATA, dataPk(targetCode, owner));
        Optional<TsRow> docRow = entityRow.isEmpty()
                ? template.getRow(TABLE_DOC_DATA, dataPk(targetCode, owner))
                : Optional.empty();
        if (entityRow.isPresent()) {
            Long status = entityRow.get().getLong("status");
            boolean active = status != null && (status == 1L || status == 100L);
            if (active) {
                return ReconcileDecision.SKIP_ACTIVE;
            }
            // 逻辑删：按主行 update_time 计宽限期
            long deletedAt = firstNonNull(entityRow.get().getLong("update_time"),
                    entityRow.get().getLong("create_time"), 0L);
            return now - deletedAt > DELETED_GRACE_MILLIS
                    ? ReconcileDecision.RELEASE : ReconcileDecision.SKIP_FRESH;
        }
        if (docRow.isPresent()) {
            Long deleted = docRow.get().getLong("deleted");
            if (deleted == null || deleted != 1L) {
                return ReconcileDecision.SKIP_ACTIVE;
            }
            long deletedAt = firstNonNull(docRow.get().getLong("update_time"),
                    docRow.get().getLong("create_time"), 0L);
            return now - deletedAt > DELETED_GRACE_MILLIS
                    ? ReconcileDecision.RELEASE : ReconcileDecision.SKIP_FRESH;
        }
        // 主行物理缺失：占位新鲜窗口内容忍（事务可能在途），超窗判定孤儿
        long age = now - firstNonNull(createTime, now, 0L);
        return age > ORPHAN_FRESH_MILLIS ? ReconcileDecision.RELEASE : ReconcileDecision.SKIP_FRESH;
    }

    /** 释放占位 + 同目标分区同步审计（operation=reconcile） */
    private void release(TsRow row) {
        String partitionKey = String.valueOf(row.getPrimaryKey().get("partition_key"));
        String value = String.valueOf(row.getPrimaryKey().get("unique_value"));
        String owner = row.getString("owner_uuid");
        String[] codes = resolveTargetAndAttr(row, partitionKey);
        if (codes == null) {
            log.warn("[reconcile] 占位行无法解析目标，跳过: partitionKey={}", partitionKey);
            return;
        }
        String targetCode = codes[0];
        String attrCode = codes[1];
        uniqueIndexStore.release(targetCode, attrCode, value);
        writeReconcileAudit(targetCode, owner, attrCode, value);
        log.info("[reconcile] 孤儿唯一占位已释放: {}/{}/{}, owner={}", targetCode, attrCode, value, owner);
    }

    /**
     * 对账审计（同目标数据分区键变体写 audit_log，与流转审计同模式，
     * design/03 §6.1 场景 3）：operation=reconcile，operator=system。
     */
    private void writeReconcileAudit(String targetCode, String ownerUuid,
                                     String attrCode, String value) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("operation", TsColumnValue.of("reconcile"));
        columns.put("target_type", TsColumnValue.of("unique_index"));
        columns.put("target_code", TsColumnValue.of(targetCode));
        columns.put("target_uuid", TsColumnValue.of(ownerUuid == null ? "" : ownerUuid));
        columns.put("operator", TsColumnValue.of("system"));
        columns.put("operate_time", TsColumnValue.of(System.currentTimeMillis()));
        columns.put("after_value", TsColumnValue.of(
                "{\"attr_code\":\"" + attrCode + "\",\"unique_value\":\"" + value + "\"}"));
        template.putRow(TABLE_AUDIT_LOG, new TsRow(TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(targetCode,
                        ownerUuid == null ? UUID.randomUUID().toString() : ownerUuid))
                .add("log_id", UUID.randomUUID().toString())
                .build(), columns));
    }

    // ==================== 小工具 ====================

    /**
     * 分区键解析：{@code {target}_{attr}_{hash4}} → [target, attr]
     * （hash 后缀 4 位不含下划线；target/attr 自身含下划线时按最后分隔符归并）。
     */
    /** 优先取占位行冗余列 [target_code, attr_code]（无歧义），缺列时分区键解析兜底 */
    static String[] resolveTargetAndAttr(TsRow row, String partitionKey) {
        String target = row.getString("target_code");
        String attr = row.getString("attr_code");
        if (target != null && !target.isBlank() && attr != null && !attr.isBlank()) {
            return new String[]{target, attr};
        }
        return parseTargetAndAttr(partitionKey);
    }

    static String[] parseTargetAndAttr(String partitionKey) {
        if (partitionKey == null) {
            return null;
        }
        int hashSep = partitionKey.lastIndexOf('_');
        if (hashSep <= 0) {
            return null;
        }
        String base = partitionKey.substring(0, hashSep);
        int codeSep = base.lastIndexOf('_');
        if (codeSep <= 0 || codeSep == base.length() - 1) {
            return null;
        }
        return new String[]{base.substring(0, codeSep), base.substring(codeSep + 1)};
    }

    private static TsPrimaryKey dataPk(String code, String uuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", PartitionKeys.dataKey(code, uuid))
                .add("uuid", uuid)
                .build();
    }

    private static long firstNonNull(Long first, Long second, long fallback) {
        if (first != null) {
            return first;
        }
        return second == null ? fallback : second;
    }

    /** 单行对账判定结果 */
    private enum ReconcileDecision {
        /** 孤儿/超宽限期：释放占位 */
        RELEASE,
        /** 新鲜窗口内（事务在途或逻辑删未超宽限）：跳过 */
        SKIP_FRESH,
        /** 属主有效：跳过 */
        SKIP_ACTIVE,
        /** 行结构异常：计错误跳过 */
        ERROR
    }
}
