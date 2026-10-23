package com.example.meta.infrastructure.index;

/**
 * 在线索引发布任务（index_publish_task）。
 *
 * <p>候选索引创建 → 全量同步 → 逻辑索引切流 → 保留旧索引回滚窗口。
 * 任务状态持久化，发布请求失败后可从任务记录追溯/重试。</p>
 */
public class IndexPublishTask {

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_READY = "READY";

    public static final String STATUS_SWITCHED = "SWITCHED";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    private String taskId;
    private String tableName;
    private String logicalIndex;
    private String candidateIndex;
    private String previousIndex;
    private String status;
    private String error;
    private String definitionJson;
    private long createTime;
    private long updateTime;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getLogicalIndex() {
        return logicalIndex;
    }

    public void setLogicalIndex(String logicalIndex) {
        this.logicalIndex = logicalIndex;
    }

    public String getCandidateIndex() {
        return candidateIndex;
    }

    public void setCandidateIndex(String candidateIndex) {
        this.candidateIndex = candidateIndex;
    }

    public String getPreviousIndex() {
        return previousIndex;
    }

    public void setPreviousIndex(String previousIndex) {
        this.previousIndex = previousIndex;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public void setDefinitionJson(String definitionJson) {
        this.definitionJson = definitionJson;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }
}
