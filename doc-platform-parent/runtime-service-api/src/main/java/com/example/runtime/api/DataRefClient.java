package com.example.runtime.api;

import com.example.common.model.ApiResponse;
import com.example.runtime.api.dto.DataRefCountDTO;
import com.example.runtime.api.fallback.DataRefClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 运行域（runtime-service）数据引用 Feign 契约（A-04，路径对齐 design/09 §2.3）。
 *
 * <p>调用方：meta-service（定义停用引用保护、数据存在性抽查，design/08 §6.1 场景 4）；
 * 接口前缀 /api/v1/internal/data/** 不出网关，仅内网服务间直达（design/08 §2.1 步骤 7）。
 * 引用计数任一 &gt; 0 时调用方抛 40010 REF_PROTECTED。</p>
 *
 * <p>超时沿用全局 connect 3s / read 5s（P-02）；FallbackFactory 全配（P-04）；
 * 仅 GET 幂等接口允许重试且 ≤ 2 次（P-05）。</p>
 */
@FeignClient(name = "runtime-service", contextId = "dataRefClient",
        fallbackFactory = DataRefClientFallbackFactory.class)
public interface DataRefClient {

    /**
     * 查询指定实体定义下有效数据量与被引用计数（引用保护，RD-03/RD-06）。
     *
     * @param code 实体定义编码
     * @return 引用计数（validDataCount / referencedCount），任一 &gt; 0 调用方拒绝停用/删除
     */
    @GetMapping("/api/v1/internal/data/count/{code}")
    ApiResponse<DataRefCountDTO> countReferences(@PathVariable("code") String code);

    /**
     * 数据存在性抽查（定义停用前置校验辅助）。
     *
     * @param code 实体定义编码
     * @param uuid 数据唯一标识
     * @return true=存在且未逻辑删除
     */
    @GetMapping("/api/v1/internal/data/{code}/{uuid}/exists")
    ApiResponse<Boolean> existsData(@PathVariable("code") String code,
                                    @PathVariable("uuid") String uuid);
}
