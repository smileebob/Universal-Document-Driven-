package com.example.runtime.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.runtime.domain.model.EntityDataRecord;

/**
 * 实体数据仓储端口（entity_data 表，infrastructure 层 Tablestore 实现）。
 *
 * <p>契约红线（design/01 §五 / TS-03）：详情与写后读一律主键 GetRow 点查，
 * 列表/检索走 {@link #searchActiveRows} 多元索引（最终一致 &lt; 3s）。</p>
 */
public interface EntityDataRepository {

    /**
     * 新增数据行（PutRow + EXPECT_NOT_EXIST，uuid 服务端生成天然不冲突，TS-01）。
     *
     * @param record 数据记录（status/version 已初始化）
     */
    void insert(EntityDataRecord record);

    /**
     * 主键点查（分区键 {@code {entity_code}_{hash4(uuid)}}）。
     *
     * @param entityCode 实体编码
     * @param uuid       数据唯一标识
     * @return 命中记录（未命中返回 empty；逻辑删行仍可命中，由调用方按 status 裁决）
     */
    Optional<EntityDataRecord> findByKey(String entityCode, String uuid);

    /**
     * version 乐观锁条件更新（EXPECT_EXIST && version == expectVersion，成功 version+1，TS-04）。
     *
     * @param entityCode    实体编码
     * @param uuid          数据唯一标识
     * @param expectVersion 读取时的 version
     * @param attributes    合并后的全量属性值
     * @param relations     重建后的引用关系
     * @param updateTime    更新时间
     * @return true=更新成功；false=version 冲突（调用方抛 40901）
     */
    boolean updateWithVersion(String entityCode, String uuid, long expectVersion,
                              Map<String, Object> attributes, List<Map<String, Object>> relations,
                              long updateTime);

    /**
     * 逻辑删除（status 置 0 的 version CAS；唯一占位按 design/03 §5.6 保留不释放）。
     *
     * @param entityCode    实体编码
     * @param uuid          数据唯一标识
     * @param expectVersion 读取时的 version
     * @param updateTime    更新时间
     * @return true=删除成功；false=version 冲突
     */
    boolean logicalDelete(String entityCode, String uuid, long expectVersion, long updateTime);

    /**
     * 统计引用指定数据的未删除行数（relations.uuid 反查，引用保护 RD-03）。
     *
     * @param targetUuid 被引用数据 uuid
     * @return 引用行数（status≠0）
     */
    long countReferencing(String targetUuid);

    /**
     * 统计指定定义下 relations.code 命中的未删除行数（定义停用引用保护，RD-06）。
     *
     * @param targetCode 定义编码
     * @return 引用行数（status≠0）
     */
    long countByRelationsCode(String targetCode);

    /**
     * 统计指定实体下未删除数据量（定义停用引用保护，RD-06）。
     *
     * @param entityCode 实体编码
     * @return 有效数据量（status≠0）
     */
    long countActiveByEntityCode(String entityCode);

    /**
     * 多元索引检索（列表/聚合取数；写后 &lt; 3s 可见，TS-05）。
     *
     * @param spec 结构化查询规格（doc-common QuerySpec）
     * @return 检索结果（行 + nextToken + totalCount）
     */
    com.example.common.infrastructure.tablestore.query.SearchResult searchActiveRows(
            com.example.common.infrastructure.tablestore.query.QuerySpec spec);
}
