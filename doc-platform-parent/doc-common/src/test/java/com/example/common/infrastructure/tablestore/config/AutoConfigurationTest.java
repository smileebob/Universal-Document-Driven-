package com.example.common.infrastructure.tablestore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import com.alicloud.openservices.tablestore.SyncClient;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.metrics.InstrumentedTablestoreTemplate;
import com.example.common.web.CommonWebAutoConfiguration;
import com.example.common.web.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * doc-common 自动装配验收（装配矩阵，阶段一出口修复）：
 * <ol>
 *   <li>mode=simulation + dev/test/sim profile：装配 InMemory 仿真实现，启动建表建索引；</li>
 *   <li>mode=simulation + 生产/缺省 profile：显式拒绝，启动失败；</li>
 *   <li>mode 缺省 + OTS 参数齐全：装配 SyncClient/OtsTablestoreTemplate/HealthIndicator/Bootstrap；</li>
 *   <li>mode 缺省 + 无 OTS 凭据：fail-fast 启动失败并给出清晰错误提示；</li>
 *   <li>mode=simulation 与 OTS 参数并存：仿真优先，不建 SyncClient；</li>
 *   <li>SERVLET 应用自动注册全局异常处理器。</li>
 * </ol>
 */
class AutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withConfiguration(AutoConfigurations.of(TablestoreAutoConfiguration.class));

    /** 密封环境：剥离系统环境变量（机器级 DOC_TABLESTORE_MODE/SPRING_PROFILES_ACTIVE 残留不得影响装配矩阵判定） */
    /** 沿异常链取根因 */
    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    @DisplayName("mode=simulation + test profile：装配 InMemory 仿真实现，启动即按 V*.json 建表建索引")
    void simulation_allowedProfile_assemblesInMemoryAndBootstraps() {
        contextRunner
                .withPropertyValues("doc.tablestore.mode=simulation", "spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SyncClient.class);
                    assertThat(context).hasSingleBean(TablestoreTemplate.class);
                    assertThat(context.getBean(TablestoreTemplate.class)).isInstanceOf(InstrumentedTablestoreTemplate.class);
                    assertThat(((InstrumentedTablestoreTemplate) context.getBean(TablestoreTemplate.class)).getDelegate())
                            .isInstanceOf(InMemoryTablestore.class);
                    assertThat(context).hasSingleBean(TablestoreBootstrap.class);
                    assertThat(context).hasSingleBean(SimulationModeGuard.class);

                    // 触发启动 Runner：仿真模式无条件建表建索引
                    context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
                    InMemoryTablestore template = (InMemoryTablestore)
                            ((InstrumentedTablestoreTemplate) context.getBean(TablestoreTemplate.class)).getDelegate();
                    assertThat(template.hasTable("entity_schema")).isTrue();
                    assertThat(template.hasTable("entity_data")).isTrue();
                    assertThat(template.hasSearchIndex("entity_schema", "entity_schema_index")).isTrue();
                });
    }

    @Test
    @DisplayName("mode=simulation + sim/dev profile 同样允许（白名单三口径）")
    void simulation_devAndSimProfiles_allowed() {
        for (String profile : new String[]{"dev", "sim"}) {
            contextRunner
                    .withPropertyValues("doc.tablestore.mode=simulation", "spring.profiles.active=" + profile)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(TablestoreTemplate.class)).isInstanceOf(InstrumentedTablestoreTemplate.class);
                        assertThat(((InstrumentedTablestoreTemplate) context.getBean(TablestoreTemplate.class)).getDelegate())
                                .isInstanceOf(InMemoryTablestore.class);
                    });
        }
    }

    @Test
    @DisplayName("mode=simulation + 生产/缺省 profile：显式拒绝，启动失败")
    void simulation_productionProfile_rejected() {
        contextRunner
                .withPropertyValues("doc.tablestore.mode=simulation")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("simulation")
                            .hasMessageContaining("dev").hasMessageContaining("sim").hasMessageContaining("test");
                });

        contextRunner
                .withPropertyValues("doc.tablestore.mode=simulation", "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("dev").hasMessageContaining("sim").hasMessageContaining("test");
                });
    }

    @Test
    @DisplayName("mode=ots（含缺省）+ endpoint/instance 齐全：装配 SyncClient 全链路")
    void otsConfigured_fullChain() {
        contextRunner
                .withPropertyValues(
                        "doc.tablestore.endpoint=https://test.example.com",
                        "doc.tablestore.instance=test-instance",
                        "doc.tablestore.access-key-id=ak",
                        "doc.tablestore.access-key-secret=sk",
                        "doc.tablestore.max-connections=32")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SyncClient.class);
                    assertThat(context).hasSingleBean(TablestoreTemplate.class);
                    assertThat(context).hasSingleBean(TablestoreHealthIndicator.class);
                    assertThat(context).hasSingleBean(TablestoreBootstrap.class);
                    TablestoreProperties properties = context.getBean(TablestoreProperties.class);
                    assertThat(properties.getMaxConnections()).isEqualTo(32);
                    assertThat(properties.getBootstrap().isAutoCreate()).isFalse();
                    context.getBean(SyncClient.class).shutdown();
                });
    }

    @Test
    @DisplayName("mode 缺省且无 OTS 凭据：fail-fast 启动失败并给出处置指引")
    void defaultMode_noCredentials_failsFastWithClearMessage() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("doc.tablestore.mode")
                    .hasMessageContaining("simulation");
        });
    }

    @Test
    @DisplayName("mode=simulation 与 OTS 参数并存：仿真优先，不建 SyncClient")
    void simulation_winsOverOtsConfig() {
        contextRunner
                .withPropertyValues(
                        "doc.tablestore.mode=simulation",
                        "spring.profiles.active=test",
                        "doc.tablestore.endpoint=https://test.example.com",
                        "doc.tablestore.instance=test-instance")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SyncClient.class);
                    assertThat(context.getBean(TablestoreTemplate.class)).isInstanceOf(InstrumentedTablestoreTemplate.class);
                    assertThat(((InstrumentedTablestoreTemplate) context.getBean(TablestoreTemplate.class)).getDelegate())
                            .isInstanceOf(InMemoryTablestore.class);
                });
    }

    @Test
    @DisplayName("SERVLET 应用自动注册 GlobalExceptionHandler；非 Web 应用不注册")
    void webExceptionHandler() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class));
    }
}
