package com.example.meta.infrastructure.index;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 在线多元索引发布（INDEX-01）。
 *
 * <p>流程：字段覆盖检查 → 创建异名候选索引 → 等待全量同步 → OTS
 * switchIndexName 切逻辑索引 → 保留旧物理索引作为回滚窗口。
 * 全程不删除当前在线索引；任务状态持久化到 index_publish_task。</p>
 *
 * <p>发布请求在候选未就绪前失败并保持元数据草稿态；已存在的旧索引持续服务。
 * 旧索引清理默认关闭，必须显式开启并经过回滚窗口。</p>
 */
@Service
public class OnlineIndexPublisher {

    private static final Logger log = LoggerFactory.getLogger(OnlineIndexPublisher.class);

    private final TablestoreTemplate template;
    private final IndexPublishTaskStore taskStore;
    private final ObjectMapper objectMapper;

    @Value("${doc.index.wait-timeout-ms:120000}")
    private long waitTimeoutMs = 120_000L;

    @Value("${doc.index.poll-interval-ms:1000}")
    private long pollIntervalMs = 1_000L;

    @Value("${doc.index.cleanup-enabled:false}")
    private boolean cleanupEnabled;

    @Value("${doc.index.retention-ms:86400000}")
    private long retentionMs = 24L * 3600 * 1000;

    public OnlineIndexPublisher(TablestoreTemplate template,
                                IndexPublishTaskStore taskStore,
                                ObjectMapper objectMapper) {
        this.template = template;
        this.taskStore = taskStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 确保在线索引覆盖期望定义；必要时候选切换。
     *
     * @return 发布任务（READY/SWITCHED）
     * @throws IndexPublishException 候选创建/同步/切流失败（旧索引保持在线）
     */
    public synchronized IndexPublishTask ensure(String tableName, SearchIndexDef definition) {
        long now = System.currentTimeMillis();
        IndexPublishTask task = new IndexPublishTask();
        task.setTaskId(tableName + "_" + definition.getName() + "_" + UUID.randomUUID());
        task.setTableName(tableName);
        task.setLogicalIndex(definition.getName());
        task.setStatus(IndexPublishTask.STATUS_RUNNING);
        task.setDefinitionJson(toJson(definition));
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskStore.save(task);
        try {
            if (template.isSearchIndexCovered(tableName, definition)) {
                complete(task, IndexPublishTask.STATUS_READY, null, null);
                log.info("[index-publish] 索引字段已覆盖，无需重建: {}.{}", tableName,
                        definition.getName());
                return task;
            }
            SearchIndexState current = template.describeSearchIndex(tableName, definition.getName());
            if (!current.exists()) {
                template.createSearchIndexIfAbsent(tableName, definition);
                waitReady(tableName, definition.getName());
                complete(task, IndexPublishTask.STATUS_READY, definition.getName(), null);
                log.info("[index-publish] 创建在线索引: {}.{}", tableName, definition.getName());
                return task;
            }
            // OTS 硬约束：兄弟索引名必须为 {source}_reindex，且只有 brother 才允许
            // switchIndexName（真实实例报错：must be {source}_reindex / cannot switch
            // non-brother indices）。
            String candidate = definition.getName() + "_reindex";
            task.setCandidateIndex(candidate);
            task.setUpdateTime(System.currentTimeMillis());
            taskStore.save(task);
            // 失败重试时可能残留同名 brother：先清理非活跃候选，再按完整定义重建
            SearchIndexState existingCandidate = template.describeSearchIndex(tableName, candidate);
            if (existingCandidate.exists()) {
                template.deleteSearchIndexIfExists(tableName, candidate);
            }
            SearchIndexDef candidateDef = copyWithName(definition, candidate);
            template.createBrotherSearchIndex(tableName, definition.getName(), candidateDef);
            waitReady(tableName, candidate);
            template.switchSearchIndex(tableName, definition.getName(), candidate);
            SearchIndexState switched = template.describeSearchIndex(tableName, definition.getName());
            String previous = switched.brotherIndexName();
            complete(task, IndexPublishTask.STATUS_SWITCHED, candidate, previous);
            log.info("[index-publish] 在线切流完成: {}.{} -> {}, previous={}",
                    tableName, definition.getName(), candidate, previous);
            return task;
        } catch (RuntimeException e) {
            task.setStatus(IndexPublishTask.STATUS_FAILED);
            task.setError(truncate(e.getMessage(), 500));
            task.setUpdateTime(System.currentTimeMillis());
            taskStore.save(task);
            log.error("[index-publish][ALERT] 在线索引发布失败，旧索引保持在线: {}.{}, task={}",
                    tableName, definition.getName(), task.getTaskId(), e);
            throw new IndexPublishException("在线索引发布失败（旧索引保持在线）: "
                    + e.getMessage(), e);
        }
    }

    /**
     * 回滚已切流任务：切回保留的兄弟索引（旧物理索引）。
     * 只有切流成功且兄弟索引仍在时可用。
     */
    public synchronized IndexPublishTask rollback(String taskId) {
        IndexPublishTask task = taskStore.find(taskId)
                .orElseThrow(() -> new IndexPublishException("索引发布任务不存在: " + taskId));
        if (!IndexPublishTask.STATUS_SWITCHED.equals(task.getStatus())
                || task.getPreviousIndex() == null || task.getPreviousIndex().isBlank()) {
            throw new IndexPublishException("任务无可回滚的旧索引: " + taskId);
        }
        template.switchSearchIndex(task.getTableName(), task.getLogicalIndex(),
                task.getPreviousIndex());
        task.setStatus(IndexPublishTask.STATUS_ROLLED_BACK);
        task.setUpdateTime(System.currentTimeMillis());
        taskStore.save(task);
        log.warn("[index-publish] 已回滚索引切流: {}.{} -> {}", task.getTableName(),
                task.getLogicalIndex(), task.getPreviousIndex());
        return task;
    }

    /** 重试失败任务（人工/运维触发；不自动无限重试）。 */
    public IndexPublishTask retry(String taskId) {
        IndexPublishTask task = taskStore.find(taskId)
                .orElseThrow(() -> new IndexPublishException("索引发布任务不存在: " + taskId));
        if (IndexPublishTask.STATUS_SWITCHED.equals(task.getStatus())
                || IndexPublishTask.STATUS_READY.equals(task.getStatus())) {
            return task;
        }
        SearchIndexDef definition = parseDefinition(task.getDefinitionJson());
        return ensure(task.getTableName(), definition);
    }

    private void complete(IndexPublishTask task, String status, String candidate,
                          String previous) {
        task.setStatus(status);
        task.setCandidateIndex(candidate);
        task.setPreviousIndex(previous);
        task.setError("");
        task.setUpdateTime(System.currentTimeMillis());
        taskStore.save(task);
    }

    private void waitReady(String tableName, String indexName) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitTimeoutMs);
        while (true) {
            SearchIndexState state = template.describeSearchIndex(tableName, indexName);
            if (state.exists() && state.ready()) {
                return;
            }
            if (state.exists() && "FAILED".equals(state.status())) {
                throw new IndexPublishException("候选索引构建失败: " + tableName + "." + indexName);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IndexPublishException("候选索引同步超时(" + waitTimeoutMs
                        + "ms): " + tableName + "." + indexName);
            }
            try {
                Thread.sleep(Math.max(100L, pollIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IndexPublishException("等待索引同步被中断", e);
            }
        }
    }

    /** 回滚窗口后清理旧物理索引（默认关闭，独立审批后开启）。 */
    @Scheduled(fixedDelayString = "${doc.index.cleanup-interval-ms:3600000}",
            initialDelayString = "${doc.index.cleanup-interval-ms:3600000}")
    public void cleanupExpiredBrothers() {
        if (!cleanupEnabled) {
            return;
        }
        long before = System.currentTimeMillis() - retentionMs;
        List<IndexPublishTask> switched = taskStore.listByStatus(
                IndexPublishTask.STATUS_SWITCHED, 50);
        for (IndexPublishTask task : switched) {
            if (task.getUpdateTime() > before
                    || task.getPreviousIndex() == null
                    || task.getPreviousIndex().isBlank()) {
                continue;
            }
            try {
                template.deleteSearchIndexIfExists(task.getTableName(), task.getPreviousIndex());
                task.setStatus("CLEANED");
                task.setUpdateTime(System.currentTimeMillis());
                taskStore.save(task);
                log.info("[index-cleanup] 已清理旧索引: {}.{}", task.getTableName(),
                        task.getPreviousIndex());
            } catch (RuntimeException e) {
                log.warn("[index-cleanup] 旧索引清理失败（下轮重试）: {}.{}", task.getTableName(),
                        task.getPreviousIndex(), e);
            }
        }
    }

    private SearchIndexDef copyWithName(SearchIndexDef source, String name) {
        SearchIndexDef copy = new SearchIndexDef(name, source.getFields());
        return copy;
    }

    private String toJson(SearchIndexDef definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IndexPublishException("索引定义序列化失败", e);
        }
    }

    private SearchIndexDef parseDefinition(String json) {
        try {
            return objectMapper.readValue(json, SearchIndexDef.class);
        } catch (JsonProcessingException e) {
            throw new IndexPublishException("索引定义反序列化失败", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 在线索引发布失败（旧索引仍在线）。 */
    public static class IndexPublishException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public IndexPublishException(String message) {
            super(message);
        }

        public IndexPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
