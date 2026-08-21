package com.example.common.infrastructure.tablestore.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Tablestore OTS 客户端配置就绪判断：
 * mode 非 simulation（ots 或缺省）且 endpoint / instance 均非空。
 */
class TablestoreConfiguredCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("doc.tablestore.mode");
        if ("simulation".equalsIgnoreCase(mode)) {
            return ConditionOutcome.noMatch("doc.tablestore.mode=simulation，跳过 OTS 客户端装配");
        }
        String endpoint = context.getEnvironment().getProperty("doc.tablestore.endpoint");
        String instance = context.getEnvironment().getProperty("doc.tablestore.instance");
        boolean configured = endpoint != null && !endpoint.isEmpty()
                && instance != null && !instance.isEmpty();
        if (configured) {
            return ConditionOutcome.match("doc.tablestore.endpoint/instance 已配置");
        }
        return ConditionOutcome.noMatch("doc.tablestore.endpoint/instance 未配置，跳过 SyncClient 装配");
    }
}
