package com.example.runtime.controller;

import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.runtime.api.DataRefClient;
import com.example.runtime.api.dto.DataRefCountDTO;
import com.example.runtime.domain.port.EntityDataRepository;

/**
 * 运行域内部契约控制器：承接 /api/v1/internal/data/**（design/08 §2.4 步骤 3）。
 *
 * <p>实现 {@link DataRefClient} 契约接口（路径对齐 design/09 §2.3），
 * 保证服务端路径与 Feign 契约严格一致；该前缀不配置网关路由，
 * 仅内网服务间 Feign 直达（A-07）。调用方：meta-service 定义停用引用保护
 * （RD-03/RD-06，计数任一 &gt; 0 调用方抛 40010）。</p>
 */
@RestController
public class InternalDataController implements DataRefClient {

    private final EntityDataRepository dataRepository;

    public InternalDataController(EntityDataRepository dataRepository) {
        this.dataRepository = dataRepository;
    }

    @Override
    public ApiResponse<DataRefCountDTO> countReferences(String code) {
        DataRefCountDTO dto = new DataRefCountDTO();
        dto.setTargetType("entity");
        dto.setTargetCode(code);
        dto.setValidDataCount(dataRepository.countActiveByEntityCode(code));
        dto.setReferencedCount(dataRepository.countByRelationsCode(code));
        return ApiResponse.ok(dto);
    }

    @Override
    public ApiResponse<Boolean> existsData(String code, String uuid) {
        boolean exists = dataRepository.findByKey(code, uuid)
                .map(record -> !record.isDeleted())
                .orElse(false);
        return ApiResponse.ok(exists);
    }
}
