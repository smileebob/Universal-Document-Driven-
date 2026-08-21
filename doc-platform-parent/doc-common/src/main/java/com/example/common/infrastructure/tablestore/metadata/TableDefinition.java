package com.example.common.infrastructure.tablestore.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 表定义（JSON 定义文件元素，TS-06：版本号只增不改、已发布定义禁改）。
 *
 * <p>承载建表所需全部元数据：主键 schema、分区键模式与 hash 位数、
 * TTL、最大版本数、多元索引定义。主键/分区键一经投产不可变更（design/03 §8.2）。</p>
 */
public class TableDefinition {

    /** TTL 永不过期 */
    public static final long TTL_FOREVER = -1L;

    private String name;

    /** 分区键模式：HASH（数据表打散）/ PLAIN（配置表单键） */
    private PartitionMode partitionMode = PartitionMode.PLAIN;

    /** HASH 模式下 hashN 位数（4 或 6，design/04 §2.3 判据） */
    private int hashLength = 4;

    /** 主键列（第一列为分区键） */
    private List<PkColumnDef> primaryKey = new ArrayList<>();

    /** 表级 TTL（秒），-1 永不过期 */
    private long ttlSeconds = TTL_FOREVER;

    /** 最大版本数（平台固定 1） */
    private int maxVersions = 1;

    /** 多元索引定义 */
    private List<SearchIndexDef> searchIndexes = new ArrayList<>();

    /** 备注 */
    private boolean localTxn = false;

    private String comment;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PartitionMode getPartitionMode() {
        return partitionMode;
    }

    public void setPartitionMode(PartitionMode partitionMode) {
        this.partitionMode = partitionMode;
    }

    public int getHashLength() {
        return hashLength;
    }

    public void setHashLength(int hashLength) {
        this.hashLength = hashLength;
    }

    public List<PkColumnDef> getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(List<PkColumnDef> primaryKey) {
        this.primaryKey = primaryKey == null ? new ArrayList<>() : primaryKey;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxVersions() {
        return maxVersions;
    }

    public void setMaxVersions(int maxVersions) {
        this.maxVersions = maxVersions;
    }

    public List<SearchIndexDef> getSearchIndexes() {
        return searchIndexes;
    }

    public void setSearchIndexes(List<SearchIndexDef> searchIndexes) {
        this.searchIndexes = searchIndexes == null ? new ArrayList<>() : searchIndexes;
    }

    public boolean isLocalTxn() {
        return localTxn;
    }

    public void setLocalTxn(boolean localTxn) {
        this.localTxn = localTxn;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * 分区键列名（第一主键列）。
     *
     * @return 分区键列名
     */
    public String partitionKeyName() {
        if (primaryKey.isEmpty()) {
            throw new IllegalStateException("表 " + name + " 未定义主键列");
        }
        return primaryKey.get(0).getName();
    }
}
