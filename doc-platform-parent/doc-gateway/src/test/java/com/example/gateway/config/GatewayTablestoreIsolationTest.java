package com.example.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.config.TablestoreAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关 Tablestore 隔离回归（评审修复 Critical-1）。
 *
 * <p>doc-common 的 tablestore SDK 依赖为 optional 不传递，网关 classpath
 * 天然无 {@code SyncClient}，{@link TablestoreAutoConfiguration} 的
 * {@code @ConditionalOnClass(SyncClient)} 不命中、整组装配跳过——
 * 网关无 OTS 配置（默认 profile）也能启动，不会仿真建 16 张表，
 * 生产 profile 下更不会触发 TablestoreModeValidator 构造即抛。</p>
 */
class GatewayTablestoreIsolationTest {

    @Test
    @DisplayName("网关 classpath 无 SyncClient：SDK optional 不传递生效")
    void syncClient_absent_from_gateway_classpath() {
        assertThatThrownBy(() -> Class.forName("com.alicloud.openservices.tablestore.SyncClient"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("Tablestore 装配在网关上下文整体跳过：无 Template/Validator，默认 profile 可启动")
    void tablestoreAutoConfiguration_skipped_in_gateway_context() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TablestoreAutoConfiguration.class))
                .run(context -> {
                    // @ConditionalOnClass 不命中：上下文正常启动且零 Tablestore Bean
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TablestoreTemplate.class);
                    assertThat(context).doesNotHaveBean(
                            "com.example.common.infrastructure.tablestore.config.TablestoreModeValidator");
                    assertThat(context).doesNotHaveBean(
                            "com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap");
                });
    }

    @Test
    @DisplayName("即使显式声明 mode=simulation（无白名单 profile）：网关依旧零装配，不误建表")
    void simulationMode_still_skipped_without_sdk_class() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TablestoreAutoConfiguration.class))
                .withPropertyValues("doc.tablestore.mode=simulation")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TablestoreTemplate.class);
                });
    }
}
