package com.example.common.infrastructure.tablestore.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * 条件注解：{@code doc.tablestore.endpoint} 与 {@code doc.tablestore.instance}
 * 均非空时生效（未配置云实例的环境不装配 SyncClient 及其门面）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(TablestoreConfiguredCondition.class)
public @interface ConditionalOnConfigured {
}
