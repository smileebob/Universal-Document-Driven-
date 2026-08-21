package com.example.meta.domain.doc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单据定义领域模型（doc_schema，DC-01~05）。
 *
 * <p>定义状态口径（design/02 §四）：100 草稿 / 1 生效（已发布）/ 0 停用；
 * 与单据实例 current_status（100 暂存/10/20/30/99）严格区分。</p>
 *
 * <p>attributes / statusDefs 以松散 Map 存储（JSON 列）：单据属性类型域
 * （string/integer/decimal/boolean/date/datetime/ref/table + sub_attributes）
 * 宽于实体属性契约，发布校验在 service 层完成。</p>
 */
public class DocSchemaDefinition {

    /** 定义状态：草稿 */
    public static final long STATUS_DRAFT = 100L;

    /** 定义状态：生效（已发布） */
    public static final long STATUS_PUBLISHED = 1L;

    /** 定义状态：停用 */
    public static final long STATUS_DISABLED = 0L;

    private String uuid;
    private String code;
    private String name;
    private String docType;
    private String description;
    private long status = STATUS_DRAFT;
    private long version;
    private List<Map<String, Object>> attributes;
    private List<Map<String, Object>> statusDefs;
    private String creator;
    private Long createTime;
    private Long updateTime;
    private Long publishedAt;

    public boolean isPublished() {
        return status == STATUS_PUBLISHED;
    }

    /** 单据定义仓储（doc_schema 单键表，实现位于 infrastructure 层） */
    public interface Repository {

        Optional<DocSchemaDefinition> findByCode(String code);

        /** EXPECT_NOT_EXIST 幂等建行；重复编码抛 TsConditionCheckException */
        void insert(DocSchemaDefinition definition);

        /** version CAS 更新 */
        void updateWithVersion(DocSchemaDefinition definition);

        /** 条件删除（EXPECT_EXIST + version 快照 CAS） */
        void deleteByCodeIfDraft(String code, long expectVersion);

        List<DocSchemaDefinition> findAll();
    }

    // ==================== getter/setter ====================

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getStatus() {
        return status;
    }

    public void setStatus(long status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public List<Map<String, Object>> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<Map<String, Object>> attributes) {
        this.attributes = attributes;
    }

    public List<Map<String, Object>> getStatusDefs() {
        return statusDefs;
    }

    public void setStatusDefs(List<Map<String, Object>> statusDefs) {
        this.statusDefs = statusDefs;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    public Long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Long publishedAt) {
        this.publishedAt = publishedAt;
    }
}
