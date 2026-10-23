package com.example.common.infrastructure.tablestore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.alicloud.openservices.tablestore.ClientConfiguration;
import com.alicloud.openservices.tablestore.SyncClient;
import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.common.infrastructure.tablestore.metadata.TableMetadataRegistry;
import com.example.common.infrastructure.tablestore.metrics.InstrumentedTablestoreTemplate;
import com.example.common.infrastructure.tablestore.metrics.TablestoreMetrics;
import com.example.common.infrastructure.tablestore.ots.OtsTablestoreTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Tablestore 自动装配（Boot 3 AutoConfiguration.imports 注册）。
 *
 * <p>装配矩阵（阶段一出口修复引入 {@code doc.tablestore.mode} 显式开关）：</p>
 * <table>
 *   <tr><th>mode</th><th>profile</th><th>endpoint/instance</th><th>结果</th></tr>
 *   <tr><td>simulation</td><td>含 dev/test/sim</td><td>任意</td><td>装配 InMemory 仿真实现，启动自动建表建索引</td></tr>
 *   <tr><td>simulation</td><td>生产/缺省</td><td>任意</td><td>显式拒绝，启动失败（{@link SimulationModeGuard}）</td></tr>
 *   <tr><td>ots / 缺省</td><td>任意</td><td>齐全</td><td>装配真实 SyncClient 及门面/探针</td></tr>
 *   <tr><td>ots / 缺省</td><td>任意</td><td>缺失</td><td>fail-fast 启动失败并给出处置指引（{@link TablestoreModeValidator}）</td></tr>
 * </table>
 *
 * <p>可观测性（任务 #20 / design/11 §4.2）：门面 Bean 一律经
 * {@link InstrumentedTablestoreTemplate} 装饰，操作延迟直方图与成功/失败计数
 * （ddp_tablestore_operation_*）经 Micrometer 暴露；无 MeterRegistry 时 NOOP。</p>
 *
 * <p>网关隔离（评审修复 Critical-1）：SDK 依赖在 doc-common 为 optional 不传递，
 * 网关 classpath 无 SyncClient。本类以字符串类名形式声明 {@code @ConditionalOnClass}（而非类字面量），
 * 避免装配条件判断时触发 SDK 类加载，网关上下文整组装配自然跳过。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(TablestoreProperties.class)
@ConditionalOnClass(name = "com.alicloud.openservices.tablestore.SyncClient")
public class TablestoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TablestoreAutoConfiguration.class);

    /** 表元数据注册表：写入侧分区键校验的事实来源（全应用单例） */
    @Bean
    @ConditionalOnMissingBean
    public TableMetadataRegistry tableMetadataRegistry() {
        return new TableMetadataRegistry();
    }

    /**
     * 装配模式 fail-fast 校验（mode 缺省/ots 而 OTS 接入参数缺失时启动失败并给出清晰错误提示）。
     * 无条件装配：eager 单例保证任何上下文刷新时优先生效。
     */
    @Bean
    public TablestoreModeValidator tablestoreModeValidator(TablestoreProperties properties,
                                                           Environment environment) {
        return new TablestoreModeValidator(properties, environment);
    }

    /** 指标打点器（无 MeterRegistry 时 NOOP，不打点不报错） */
    private static TablestoreMetrics metricsOf(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? TablestoreMetrics.NOOP : new TablestoreMetrics(registry);
    }

    /**
     * InMemory 仿真装配组（{@code doc.tablestore.mode=simulation} 时生效）。
     *
     * <p>profile 安全防护由 {@link SimulationModeGuard} 承担：仅 dev/test/sim 允许，
     * 生产 profile 下构造即抛异常、启动失败。仿真模式启动一律执行 V*.json 建表建索引
     * （内存存储重启即空，不执行 bootstrap 服务不可用，故不受 bootstrap.auto-create 开关影响）。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "doc.tablestore", name = "mode", havingValue = "simulation")
    static class SimulationConfiguration {

        /** profile 防护 Bean（eager 实例化，非法 profile 启动失败） */
        @Bean
        SimulationModeGuard simulationModeGuard(Environment environment) {
            return new SimulationModeGuard(environment);
        }

        /** TablestoreTemplate 仿真实现（防腐层同一门面契约，F-06；指标装饰器包裹） */
        @Bean
        @ConditionalOnMissingBean(TablestoreTemplate.class)
        TablestoreTemplate inMemoryTablestoreTemplate(TableMetadataRegistry metadataRegistry,
                                                      SimulationModeGuard simulationModeGuard,
                                                      ObjectProvider<MeterRegistry> registryProvider) {
            log.info("[tablestore] 装配 InMemory 仿真实现（mode=simulation，仅限 dev/test/sim 环境；"
                    + "内存存储无持久化，禁止用于生产）");
            return new InstrumentedTablestoreTemplate(new InMemoryTablestore(metadataRegistry),
                    metricsOf(registryProvider));
        }

        /** 启动引导（复用现有逻辑：加载 classpath tablestore/V*.json 幂等建表建索引） */
        @Bean
        @ConditionalOnMissingBean
        TablestoreBootstrap tablestoreBootstrap(TablestoreTemplate template) {
            return new TablestoreBootstrap(template);
        }

        /** 仿真模式启动即建表建索引（无条件执行） */
        @Bean
        ApplicationRunner tablestoreSimulationBootstrapRunner(TablestoreBootstrap bootstrap) {
            return args -> bootstrap.run();
        }
    }

    /** 真实客户端装配组（mode=ots/缺省 且 endpoint/instance 配置齐全时生效，TS-07 参数治理） */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnConfigured
    static class OtsClientConfiguration {

        private static final Logger otsLog = LoggerFactory.getLogger(OtsClientConfiguration.class);

        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean
        public SyncClient syncClient(TablestoreProperties properties) {
            ClientConfiguration configuration = new ClientConfiguration();
            configuration.setConnectionTimeoutInMillisecond(properties.getConnectionTimeoutMs());
            configuration.setSocketTimeoutInMillisecond(properties.getSocketTimeoutMs());
            configuration.setMaxConnections(properties.getMaxConnections());
            otsLog.info("[tablestore] SyncClient 装配: endpoint={}, instance={}, maxConnections={}",
                    properties.getEndpoint(), properties.getInstance(), properties.getMaxConnections());
            return new SyncClient(properties.getEndpoint(), properties.getAccessKeyId(),
                    properties.getAccessKeySecret(), properties.getInstance(), configuration);
        }

        /** TablestoreTemplate 唯一生产实现（防腐层唯一入口，F-06；指标装饰器包裹） */
        @Bean
        @ConditionalOnMissingBean(TablestoreTemplate.class)
        public TablestoreTemplate tablestoreTemplate(SyncClient syncClient,
                                                     TableMetadataRegistry metadataRegistry,
                                                     ObjectProvider<MeterRegistry> registryProvider) {
            return new InstrumentedTablestoreTemplate(
                    new OtsTablestoreTemplate(syncClient, metadataRegistry),
                    metricsOf(registryProvider));
        }

        /** 健康探针（轻量 ListTable，J-05 变通） */
        @Bean
        @ConditionalOnMissingBean
        public TablestoreHealthIndicator tablestoreHealthIndicator(SyncClient syncClient,
                                                                   TablestoreProperties properties) {
            return new TablestoreHealthIndicator(syncClient, properties);
        }

        /** dev/test 自动建表（幂等）：auto-create=true 时启动触发（TS-06） */
        @Bean
        @ConditionalOnMissingBean
        public TablestoreBootstrap tablestoreBootstrap(TablestoreTemplate template) {
            return new TablestoreBootstrap(template);
        }

        @Bean
        ApplicationRunner tablestoreBootstrapRunner(TablestoreBootstrap bootstrap,
                                                    TablestoreProperties properties) {
            return args -> {
                if (properties.getBootstrap().isAutoCreate()) {
                    bootstrap.run();
                } else {
                    otsLog.info("[tablestore] bootstrap.auto-create=false，跳过启动建表（生产由流水线执行）");
                }
            };
        }
    }
}
