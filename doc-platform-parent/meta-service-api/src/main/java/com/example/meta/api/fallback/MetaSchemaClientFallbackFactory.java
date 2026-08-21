package com.example.meta.api.fallback;

import java.util.List;

import com.example.common.model.ApiResponse;
import com.example.meta.api.MetaSchemaClient;
import com.example.meta.api.dto.DocRuleConfigDTO;
import com.example.meta.api.dto.DocSchemaDTO;
import com.example.meta.api.dto.EntitySchemaDTO;
import com.example.meta.api.dto.RuleLibDTO;
import com.example.meta.api.dto.StateMachineDTO;
import com.example.meta.api.dto.TxConfigDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * {@link MetaSchemaClient} 降级工厂（P-04：FallbackFactory 全配）。
 *
 * <p>降级语义：返回明确错误码（50000 SYSTEM_ERROR，由调用方裁决 42201/50000），
 * 不吞异常——cause 记录 ERROR 日志保留排查线索（H-01/H-04）。
 * Sentinel 集成按 P-04（feign.sentinel.enabled 启用时生效）。</p>
 *
 * <p>注册方式：本类不加 {@code @Component}（契约模块不进调用方组件扫描路径），
 * 由调用方（runtime-service）以 {@code @Bean} 显式注册。</p>
 */
public class MetaSchemaClientFallbackFactory implements FallbackFactory<MetaSchemaClient> {

    private static final Logger log = LoggerFactory.getLogger(MetaSchemaClientFallbackFactory.class);

    /** 系统错误码（PRD 12 第三节，ErrorCode 枚举收口） */
    private static final int CODE_SYSTEM_ERROR = 50000;

    @Override
    public MetaSchemaClient create(Throwable cause) {
        log.error("[feign-fallback] meta-service MetaSchemaClient 调用失败，触发降级", cause);
        return new MetaSchemaClient() {

            @Override
            public ApiResponse<DocSchemaDTO> getDocSchema(String docCode) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：获取单据定义失败");
            }

            @Override
            public ApiResponse<List<DocSchemaDTO>> listPublishedDocSchemas() {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：拉取已发布单据定义失败");
            }

            @Override
            public ApiResponse<StateMachineDTO> getStateMachine(String docCode) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：获取状态机失败");
            }

            @Override
            public ApiResponse<DocRuleConfigDTO> getDocRuleConfig(String docCode) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：获取规则绑定失败");
            }

            @Override
            public ApiResponse<TxConfigDTO> getTxConfig(String docCode) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：获取事务配置失败");
            }

            @Override
            public ApiResponse<RuleLibDTO> getRuleLib(String uuid) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：获取规则定义失败");
            }

            @Override
            public ApiResponse<EntitySchemaDTO> getEntitySchema(String code) {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：获取实体定义失败");
            }

            @Override
            public ApiResponse<List<EntitySchemaDTO>> listPublishedEntitySchemas() {
                return ApiResponse.fail(CODE_SYSTEM_ERROR, "meta-service 不可用：拉取已发布实体定义失败");
            }
        };
    }
}
