package com.example.meta.domain.schema;

import java.util.List;
import java.util.Optional;

/**
 * 实体定义仓储端口（domain 层，实现位于 infrastructure/repository，F-06）。
 *
 * <p>实现约束：仅经 TablestoreTemplate 门面访问 entity_schema 表；
 * 条件写冲突（EXPECT_NOT_EXIST / version CAS）以 TsConditionCheckException
 * 语义上抛，由上层映射 40006/40901。</p>
 */
public interface EntitySchemaRepository {

    /**
     * 按编码点查（写后读强一致，GetRow）。
     *
     * @param code 实体编码
     * @return 定义（未命中 empty）
     */
    Optional<EntitySchema> findByCode(String code);

    /**
     * 新建（EXPECT_NOT_EXIST 幂等建行；重复时抛 TsConditionCheckException）。
     *
     * @param schema 定义（status=DRAFT，version=0）
     */
    void insert(EntitySchema schema);

    /**
     * version 乐观锁全列更新（TS-04；成功后行内 version+1，冲突抛条件异常）。
     *
     * @param schema 定义（version 为读取时快照，作为 CAS 期望值）
     */
    void updateWithVersion(EntitySchema schema);

    /**
     * 删除定义行（仅草稿态调用由 service 把关）。
     *
     * @param code 实体编码
     */
    void deleteByCode(String code);

    /**
     * 条件删除定义行（评审修复 Major-6：TOCTOU 防御）：
     * EXPECT_EXIST + version 列等值（期望读取快照版本）。
     *
     * <p>条件失败（行已被并发修改/删除）抛 TsConditionCheckException，
     * 由 service 映射 40901。</p>
     *
     * @param code          实体编码
     * @param expectVersion 读取快照的 version（CAS 期望值）
     */
    void deleteByCodeIfDraft(String code, long expectVersion);

    /**
     * 全量加载（GetRange，配置表单键模式行数百级；启动预热/列表/菜单聚合用）。
     *
     * @return 全部定义（按主键字典序）
     */
    List<EntitySchema> findAll();
}
