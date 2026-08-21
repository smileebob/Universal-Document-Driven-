package com.example.runtime.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体数据记录（运行域领域模型，entity_data 行的业务投影）。
 *
 * <p>与 Tablestore 行结构的转换集中在 infrastructure 层仓储实现（design/03 §2.2）：
 * status 取值 1=有效、0=逻辑删、100=暂存；version 初始 1，条件更新成功 +1（TS-04）。</p>
 */
public final class EntityDataRecord {

    /** 状态：有效 */
    public static final int STATUS_ACTIVE = 1;

    /** 状态：逻辑删除 */
    public static final int STATUS_DELETED = 0;

    /** 实体数据固定 entity_type 取值 */
    public static final String TYPE_ENTITY = "entity";

    /** 实例唯一标识（服务端生成 UUID v4） */
    private final String uuid;

    /** 实体编码 */
    private final String entityCode;

    /** 数据类型：entity / doc */
    private final String entityType;

    /** 状态：1=有效、0=逻辑删、100=暂存 */
    private final int status;

    /** 乐观锁版本（初始 1） */
    private final long version;

    /** 业务属性值（schema 驱动，保持提交顺序） */
    private final Map<String, Object> attributes;

    /** 引用关系列表（REF 属性投影：type/code/uuid，引用保护反查依据） */
    private final List<Map<String, Object>> relations;

    /** 创建人（X-User-Id 权威口径，design/09 §3.8） */
    private final String creator;

    /** 创建部门 */
    private final String createDept;

    /** 创建时间（毫秒时间戳） */
    private final long createTime;

    /** 更新时间（毫秒时间戳） */
    private final long updateTime;

    public EntityDataRecord(String uuid, String entityCode, String entityType, int status,
                            long version, Map<String, Object> attributes,
                            List<Map<String, Object>> relations, String creator,
                            String createDept, long createTime, long updateTime) {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("数据 uuid 不可为空");
        }
        if (entityCode == null || entityCode.isBlank()) {
            throw new IllegalArgumentException("实体编码不可为空");
        }
        this.uuid = uuid;
        this.entityCode = entityCode;
        this.entityType = entityType == null ? TYPE_ENTITY : entityType;
        this.status = status;
        this.version = version;
        this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        this.relations = relations == null ? List.of() : List.copyOf(relations);
        this.creator = creator;
        this.createDept = createDept;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public String getUuid() {
        return uuid;
    }

    public String getEntityCode() {
        return entityCode;
    }

    public String getEntityType() {
        return entityType;
    }

    public int getStatus() {
        return status;
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

    /** 是否逻辑删除态 */
    public boolean isDeleted() {
        return status == STATUS_DELETED;
    }
}
