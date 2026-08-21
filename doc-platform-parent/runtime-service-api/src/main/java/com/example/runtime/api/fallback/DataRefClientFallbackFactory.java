package com.example.runtime.api.fallback;

import com.example.common.model.ApiResponse;
import com.example.runtime.api.DataRefClient;
import com.example.runtime.api.dto.DataRefCountDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * {@link DataRefClient} 降级工厂（P-04：FallbackFactory 全配）。
 *
 * <p>降级语义：返回明确错误码（50000 SYSTEM_ERROR，由调用方裁决），
 * 不吞异常——cause 记录 ERROR 日志保留排查线索（H-01/H-04）。
 * 引用保护场景调用方应将降级视为「不可判定」而阻断停用/删除（fail-safe）。</p>
 *
 * <p>注册方式：本类不加 {@code @Component}（契约模块不进调用方组件扫描路径），
 * 由调用方（meta-service）以 {@code @Bean} 显式注册。</p>
 */
public class DataRefClientFallbackFactory implements FallbackFactory<DataRefClient> {

    private static final Logger log = LoggerFactory.getLogger(DataRefClientFallbackFactory.class);

    /** 系统错误码（PRD 12 第三节，阶段一由 ErrorCode 枚举收口） */
    private static final int CODE_SYSTEM_ERROR = 50000;

    @Override
    public DataRefClient create(Throwable cause) {
        log.error("[feign-fallback] runtime-service DataRefClient 调用失败，触发降级", cause);
        return new DataRefClient() {

            @Override
            public ApiResponse<DataRefCountDTO> countReferences(String code) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "runtime-service 不可用：引用计数查询失败");
            }

            @Override
            public ApiResponse<Boolean> existsData(String code, String uuid) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "runtime-service 不可用：数据存在性查询失败");
            }
        };
    }
}
