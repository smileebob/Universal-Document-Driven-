package com.example.runtime.domain.port;

import com.example.runtime.domain.model.EntitySchema;

/**
 * 实体定义回源端口（防腐层接口，infrastructure 层 MetaSchemaGateway 实现）。
 *
 * <p>meta-service-api 的 EntitySchemaDTO 当前为占位骨架，与阶段一目标契约存在差异；
 * 经 leader 裁决批准以 Gateway 单类收口做 DTO→领域模型适配，
 * domain/service 不直接依赖 Feign DTO 的具体形态（A-04 防腐）。</p>
 */
public interface SchemaGateway {

    /**
     * 查询生效的实体定义。
     *
     * @param entityCode 实体编码
     * @return 实体定义（领域模型）
     * @throws com.example.common.exception.BizException NOT_FOUND（40401）定义不存在或已停用；
     *                                                   SYSTEM_ERROR（50000）回源失败
     */
    EntitySchema getEntitySchema(String entityCode);
}
