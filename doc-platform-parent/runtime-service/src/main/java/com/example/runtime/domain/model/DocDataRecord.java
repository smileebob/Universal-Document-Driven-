package com.example.runtime.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 单据数据运行域模型（doc_data 主行映射，RD-05~08）。
 *
 * <p>状态口径（prd/12 §四）：current_status 取 status_defs 字典值
 * （100 暂存初始 / 10、20、30 业务态 / 99 关闭终态）；version 乐观锁；
 * deleted=1 为逻辑删（仅暂存态允许删除，40009）。</p>
 */
public class DocDataRecord {

    /** 逻辑删除标记值 */
    public static final long DELETED = 1L;

    /** 有效行标记值 */
    public static final long ACTIVE = 0L;

    private final String uuid;
    private final String docCode;
    private final long currentStatus;
    private final long version;
    private final Map<String, Object> attributes;
    private final List<Map<String, Object>> relations;
    private final boolean deleted;
    private final String creator;
    private final String createDept;
    private final long createTime;
    private final long updateTime;

    public DocDataRecord(String uuid, String docCode, long currentStatus, long version,
                         Map<String, Object> attributes, List<Map<String, Object>> relations,
                         boolean deleted, String creator, String createDept,
                         long createTime, long updateTime) {
        this.uuid = uuid;
        this.docCode = docCode;
        this.currentStatus = currentStatus;
        this.version = version;
        this.attributes = attributes == null ? Map.of() : attributes;
        this.relations = relations == null ? List.of() : relations;
        this.deleted = deleted;
        this.creator = creator;
        this.createDept = createDept;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public String getUuid() {
        return uuid;
    }

    public String getDocCode() {
        return docCode;
    }

    public long getCurrentStatus() {
        return currentStatus;
    }

    public long getVersion() {
        return version;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public List<Map<String, Object>> getRelations() {
        return relations;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public String getCreator() {
        return creator;
    }

    public String getCreateDept() {
        return createDept;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }
}
