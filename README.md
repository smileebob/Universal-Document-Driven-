# 通用单据驱动平台（Universal Document-Driven Platform）

一个**元数据配置驱动**的通用单据运行时平台：通过实体（Entity）、单据（Document）定义、状态机、业务规则与事务编排的纯配置化建模，让业务单据类应用无需编写领域代码即可上线运行。

核心能力：

- **实体与单据建模**：字段定义、校验规则、引用关系、唯一约束，全部由元数据驱动；
- **状态机引擎**：单据状态、流转事件、并发与一致性控制（乐观并发 + 状态流转时间线）；
- **规则引擎**：校验规则、计算规则与状态/事件绑定；
- **事务与消息**：Outbox 事务消息、Saga 编排、缓存失效广播；
- **运行时数据服务**：单据创建/查询/状态流转/明细时间线，全部走统一运行时接口；
- **运营监控**：健康检查、可观测性端点。

技术栈：Spring Boot 3.5 / Spring Cloud Alibaba（Nacos / Sentinel / Gateway）、阿里云 Tablestore（含本地 simulation 模式）、Redis、RocketMQ（含本地 mock 模式）、Vue 3 + Vite + Ant Design Vue + AntV G6。

## 模块结构

```
Document-Driven/
├── doc-platform-parent/        # 后端（Maven 聚合工程）
│   ├── doc-gateway/            # API 网关（路由、JWT 认证、限流）
│   ├── meta-service/           # 元数据服务（实体/单据定义/状态机/规则配置管理）
│   ├── runtime-service/        # 运行时服务（单据数据、状态流转、事务）
│   ├── meta-service-api/       # meta-service 对外契约（Feign/DTO）
│   ├── runtime-service-api/    # runtime-service 对外契约（Feign/DTO）
│   ├── doc-common/             # 公共库（Tablestore 防腐层、缓存、消息门面、领域基础）
│   ├── deploy/                 # 部署脚本（app.sh：启动/停止/状态/重启）
│   └── docs/                   # 工程文档（含提交前审核清单）
└── doc-web/                    # 前端（Vue 3 + Vite，配置控制台 + 运行时业务页面 + 运营监控）
```

## 快速开始

### 零云资源本地运行（dev profile + simulation 模式）

后端支持**零云依赖**启动：dev profile 下 Tablestore 默认走内存 simulation 模式（`OTS_MODE=simulation`），消息默认走 mock 模式（`DOC_MQ_MODE=mock`），无需任何阿里云资源即可本地跑通全流程。

```bash
# 1. 构建（在 doc-platform-parent 目录下）
mvn clean package -DskipTests

# 2. 依次启动三个服务（端口 8080/8081/8082）
java -jar doc-gateway/target/doc-gateway-*.jar        # 网关 :8080
java -jar meta-service/target/meta-service-*.jar      # 元数据服务 :8081
java -jar runtime-service/target/runtime-service-*.jar # 运行时服务 :8082

# 3. 启动前端（端口 5173）
cd doc-web
npm install
npm run dev
```

> 注：dev profile 下若本机无 Nacos/Redis，相关组件按 application.yml 中的占位默认值降级运行；如需接入真实 Nacos/Redis/Tablestore/RocketMQ，通过环境变量注入即可（见下节）。

### 敏感配置一律环境变量注入

本项目遵循**敏感值零明文**原则：所有密钥、接入点、账号密码均不写入代码与配置文件，统一通过环境变量注入（application.yml 中以 `${ENV:默认值}` 占位符引用，未注入时回落到仅限开发环境的本地默认值）。

## 环境变量清单

| 变量 | 说明 |
| --- | --- |
| `NACOS_SERVER_ADDR` | Nacos 注册/配置中心地址 |
| `NACOS_NAMESPACE` | Nacos 命名空间 |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址与端口 |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | Redis 认证账号（ACL） |
| `REDIS_TIMEOUT_MS` / `REDIS_CONNECT_TIMEOUT_MS` | Redis 超时参数 |
| `OTS_MODE` | Tablestore 装配模式：`ots`（真实实例，默认）/ `simulation`（内存模拟，仅 dev/test） |
| `OTS_ENDPOINT` / `OTS_INSTANCE` | Tablestore 实例接入点与实例名 |
| `OTS_ACCESS_KEY_ID` / `OTS_ACCESS_KEY_SECRET` | Tablestore 访问密钥 |
| `OTS_AUTO_CREATE` | 启动自动建表开关（dev/test 用，生产禁用） |
| `DOC_MQ_MODE` | 消息门面模式：`mock`（默认，进程内模拟 + Redis Pub/Sub 桥接）/ `rocketmq`（真实 RocketMQ） |
| `ROCKETMQ_ENDPOINT` | RocketMQ 接入点（name-server 语义） |
| `ROCKETMQ_ACCESS_KEY` / `ROCKETMQ_SECRET_KEY` | RocketMQ 访问密钥 |
| `GATEWAY_JWT_SECRET` | 网关 JWT 签名密钥（HS256） |
| `GATEWAY_AUTH_ENABLED` | 网关认证开关 |

> 权威清单与注入方式（export / `/etc/environment` / systemd EnvironmentFile）见 [`doc-platform-parent/deploy/app.sh`](doc-platform-parent/deploy/app.sh) 的「环境变量注入说明」段落。

### 运行时调优变量（可选，均有默认值）

以下变量用于生产环境调优，未注入时均回落到配置文件中的默认值：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `OUTBOX_POLL_INTERVAL_MS` | `1000` | Outbox 中继轮询间隔（毫秒） |
| `OUTBOX_BATCH_SIZE` | `50` | Outbox 中继单批投递条数 |
| `OUTBOX_MAX_RETRY` | `16` | Outbox 消息最大重试次数 |
| `DOC_CACHE_L1_TTL_MINUTES` | `60` | 单据定义 L1（Redis）缓存 TTL（分钟） |
| `DOC_CACHE_L2_TTL_MINUTES` | `5` | 单据定义 L2（本地 Caffeine）缓存 TTL（分钟） |
| `DOC_CACHE_WARMUP_ENABLED` | `true` | 启动时缓存预热开关 |
| `DOC_LOCK_WAIT_MS` | `0` | 状态流转分布式锁获取等待时间（毫秒） |
| `DOC_LOCK_LEASE_MS` | `30000` | 状态流转分布式锁租约时长（毫秒） |
| `MQ_TOPIC_DOC_EVENT` | `doc-event` | Outbox 投递的单据事件 Topic |
| `MQ_TOPIC_CACHE_INVALIDATE` | `doc-cache-invalidate` | 缓存失效广播 Topic |
| `MQ_GROUP_CACHE_INVALIDATE` | `doc-cache-invalidate-cg` | 缓存失效广播消费组 |
| `SCHEMA_CACHE_TTL_SECONDS` | `300` | meta-service schema 缓存 TTL（秒，广播失效的 TTL 兜底） |
| `RECONCILE_UNIQUE_ORPHAN` | `false` | 唯一约束孤儿记录定时对账开关（手动触发接口不受开关限制） |
| `DOC_SCHEMA_INVALIDATION_MQ` | `true` | schema 失效 MQ 广播开关（关闭时退化为 Caffeine TTL 兜底） |

## 贡献指引

欢迎提交 Issue 与 Pull Request。请注意：

- **任何提交须先通过 [`doc-platform-parent/docs/commit-review-checklist.md`](doc-platform-parent/docs/commit-review-checklist.md) 定义的提交前审核清单**（黑名单路径检查、敏感串零容忍扫描、入库体积抽查），未通过审核清单的提交一律拒绝；
- 后端遵循 Spring Cloud Alibaba 规范约束，前端遵循 Vue 规范，详见仓库内文档；
- 提交信息请清晰描述变更动机与影响面。

## 许可证

本项目基于 **GPL-3.0-or-later**（GNU General Public License v3.0 or later）开源，完整许可证文本见 [LICENSE](LICENSE)。

Copyright © 2026 Document-Driven Platform contributors.
