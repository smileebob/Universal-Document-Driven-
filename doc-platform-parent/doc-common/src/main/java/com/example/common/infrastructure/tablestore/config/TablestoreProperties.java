package com.example.common.infrastructure.tablestore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tablestore 客户端配置（绑定 {@code doc.tablestore.*}，TS-07 客户端参数治理）。
 *
 * <p>敏感值（access-key-id/secret）一律经环境变量注入，Nacos 与代码零明文（R-05）。</p>
 */
@ConfigurationProperties(prefix = "doc.tablestore")
public class TablestoreProperties {

    /**
     * 装配模式（显式开关，阶段一出口修复引入）：
     * <ul>
     *   <li>{@link Mode#OTS}（缺省语义）：装配真实 Tablestore 客户端，要求 endpoint/instance 齐全；</li>
     *   <li>{@link Mode#SIMULATION}：装配 InMemory 仿真实现（启动按 V*.json 自动建表建索引），
     *       仅在 spring profile 含 dev/test/sim 时允许，生产 profile 显式拒绝并启动失败。</li>
     * </ul>
     */
    public enum Mode {
        /** 真实 Tablestore（OTS）客户端 */
        OTS,
        /** InMemory 仿真（仅 dev/test/sim profile） */
        SIMULATION
    }

    /** 装配模式；未设置时按 OTS 语义处理（缺省即生产口径） */
    private Mode mode;

    /** 实例接入点（空表示未配置，跳过 SyncClient 装配——纯仿真测试场景） */
    private String endpoint;

    /** 实例名 */
    private String instance;

    /** AccessKey ID（环境变量 OTS_ACCESS_KEY_ID 注入） */
    private String accessKeyId;

    /** AccessKey Secret（环境变量 OTS_ACCESS_KEY_SECRET 注入） */
    private String accessKeySecret;

    /** 连接超时（毫秒，TS-07） */
    private int connectionTimeoutMs = 3000;

    /** Socket 超时（毫秒，TS-07） */
    private int socketTimeoutMs = 5000;

    /** 最大连接数（TS-07） */
    private int maxConnections = 64;

    /** 启动装配（dev/test 自动建表） */
    private final Bootstrap bootstrap = new Bootstrap();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** 解析后的装配模式：未显式配置时按 OTS（生产口径）处理 */
    public Mode resolvedMode() {
        return mode == null ? Mode.OTS : mode;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    /** 是否具备装配真实客户端的最小配置 */
    public boolean isConfigured() {
        return endpoint != null && !endpoint.isEmpty()
                && instance != null && !instance.isEmpty();
    }

    /** 启动建表配置（TS-06：生产由流水线显式执行，dev/test 可开自动建表） */
    public static class Bootstrap {

        /** 启动时按 JSON 定义自动建表/建索引（幂等）；默认关闭 */
        private boolean autoCreate;

        public boolean isAutoCreate() {
            return autoCreate;
        }

        public void setAutoCreate(boolean autoCreate) {
            this.autoCreate = autoCreate;
        }
    }
}
