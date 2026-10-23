**中文** | [English](README.en.md)

# 通用单据驱动平台（Universal Document-Driven Platform）

## 项目定位

通用单据驱动平台是一个**开源的单据/订单共享服务**，面向制造业与销售领域的业务系统——CRM、WMS、ERP、订单管理等。它把实体/单据建模、状态机、校验规则与事务编排收敛为一套统一能力，让业务系统复用同一套单据语义，而不是各自重复实现。

平台要解决的两个问题：

- **防止烟囱式应用**：避免每个系统各写一套单据逻辑，导致标准不一、语义分裂、难以集成；
- **降低数据治理成本**：单据模型、状态语义与字段约束全局一致，跨系统汇总、追溯和对账更容易。

**适合**：需要快速构建或统一多套“带生命周期的业务单据”的开发/实施团队。
**不适合**：文件（PDF/Word）管理、完整 ERP、复杂多级审批工作流、多租户 SaaS 平台。这些不是当前实现目标。

## 设计思想

1. **元数据是一份可执行业务定义**：字段、类型、必填、只读、唯一、精度、引用、状态与校验规则都由配置产生，前端渲染与服务端校验共用同一份定义。
2. **配置域与运行域分离**：`meta-service` 管理定义与发布，`runtime-service` 解释定义执行业务，`doc-gateway` 是唯一外部入口。
3. **状态流转是业务命令**：单据状态只能通过流转入口改变，普通属性更新不能绕过状态机、规则、并发控制和历史记录。
4. **按物理边界设计一致性**：主键点查强一致；列表依赖异步索引；单分区事务、Saga 补偿、Outbox 事件各自有明确边界，不把它们说成全局强一致事务。
5. **先契约、再实现、后验收**：需求 → 设计 → 接口/存储约束 → 分阶段交付 → 真实契约验证；AI 辅助开发只是研发方式，不是平台内置业务能力。

```text
配置控制台 ──定义/发布──▶ meta-service（实体、单据、状态机、规则、事务步骤）
                                      │ 发布失效广播
业务页面/外部系统 ──▶ doc-gateway ──▶ runtime-service（数据、流转、查询、事件、Saga）
                                      │
                          Tablestore / Redis / RocketMQ
```

## 核心能力与边界

| 能力 | 当前实现 | 边界 |
|---|---|---|
| 实体与单据建模 | 头属性、明细表、引用、唯一、必填、只读、长度/精度配置 | 不支持公式计算、自动赋值与字段联动 |
| 动态页面 | 元数据驱动列表、表单、详情、状态时间线 | 特殊交互页面仍需定制开发 |
| 状态机 | 流转路径配置、合法性校验、并发控制、状态历史 | 不是通用 BPM/多级审批引擎 |
| 校验规则 | SpEL 表达式校验 + 接口调用校验，按状态/新增动作绑定 | 规则只做校验，不修改业务数据 |
| 事务与事件 | 同表不可变操作日志、外部步骤顺序执行、失败逆序冲正、按单据顺序投递、死信阻断 | 历史/审计投影最终一致；Saga 未知结果转人工 |
| 查询与审计 | 统一查询 DSL、分页/筛选/排序、操作审计与状态历史 | 深分页受索引语义限制；审计保留策略需按部署配置 |
| 权限 | 平台管理员 / 业务管理员 / 业务用户的功能级授权 | 多租户、组织隔离、字段级权限不在本期 |

## 一条业务主线

1. 管理员定义基础实体（产品、客户等）并发布；
2. 定义单据头、明细与状态集合，配置状态机、校验规则和可选外部事务步骤；
3. 发布定义并挂载菜单；
4. 业务用户创建单据，执行提交/审核/下达等流转；
5. 流转触发规则校验、并发控制、外部步骤与事件；成功后可在详情、历史与运营页面追溯。

## 技术栈

Java 17 / Spring Boot 3.5 / Spring Cloud Alibaba 2025（Nacos、Sentinel、Gateway）/ 阿里云 Tablestore（支持本地 simulation）/ Redis / RocketMQ（支持本地 mock）/ Vue 3 + Vite + Ant Design Vue + AntV G6。

## 模块结构

```text
Document-Driven/
├── doc-platform-parent/          # 后端（Maven 聚合工程）
│   ├── doc-gateway/              # 网关：路由、JWT 认证、限流、CORS
│   ├── meta-service/             # 配置域：定义、发布、菜单、缓存失效
│   ├── runtime-service/          # 运行域：数据、流转、查询、事件、Saga、运营
│   ├── meta-service-api/         # 配置域 Feign 契约与 DTO
│   ├── runtime-service-api/      # 运行域 Feign 契约与 DTO
│   ├── doc-common/               # 存储/缓存/消息防腐层、统一异常与安全组件
│   ├── deploy/                   # 开发/联调部署脚本
│   └── docs/                     # 提交审核清单、OpenAPI、性能基线、告警规则
└── doc-web/                      # Vue 3 前端（配置控制台 + 运行页面 + 运营监控）
```

## 快速开始

### 前置条件

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK / Maven | JDK 17+、Maven 3.8+ | 后端构建 |
| Node.js / npm | Node 20+ | 前端构建 |
| Nacos | 本地单机即可 | 网关与服务的注册/发现必需 |
| Redis | 本地单机即可 | 分布式锁、缓存与 mock 消息桥接 |

**“零云资源”不等于“零本地中间件”**：dev 模式下 Tablestore 使用内存仿真、RocketMQ 使用进程内 mock，但 Nacos 与 Redis 仍需本地可用（默认 `127.0.0.1:8848` / `127.0.0.1:6379`）。仿真数据重启即丢失，mock 消息不证明真实 MQ 投递语义。

### 1. 构建后端

```bash
cd doc-platform-parent
mvn clean package -DskipTests
```

### 2. 启动三个服务（各开一个终端，均设置 dev profile）

```bash
# 环境变量（三个终端一致；bash 写法）
export SPRING_PROFILES_ACTIVE=dev
export OTS_MODE=simulation
export DOC_MQ_MODE=mock
```

```bash
# 终端 A：网关（:8080，dev profile 默认关闭认证，便于本地演示）
java -jar doc-gateway/target/doc-gateway-*.jar

# 终端 B：配置域（:8081）
java -jar meta-service/target/meta-service-*.jar

# 终端 C：运行域（:8082）
java -jar runtime-service/target/runtime-service-*.jar
```

PowerShell 对应写法：`$env:SPRING_PROFILES_ACTIVE="dev"; $env:OTS_MODE="simulation"; $env:DOC_MQ_MODE="mock"`，然后在各终端启动。

启动成功的判断标准：三个进程日志无持续异常，且 `curl http://localhost:8080/actuator/health`、`:8081`、`:8082` 返回 `UP`。首个管理员定义需通过 API 或前端创建；项目不内置可复用的生产账号。

### 3. 启动前端

```bash
cd doc-web        # 从仓库根目录执行
npm install
npm run dev
```

打开 `http://localhost:5173`。本地登录为**演示登录**（默认允许选择角色，仅用于开发演示，不代表真实权限体系）；生产必须接入真实身份提供方并开启网关认证。

### 4. 停止

开发脚本可按服务停止：`cd doc-platform-parent && ./deploy/app.sh stop doc-gateway`（脚本仅供开发/联调；生产请使用 systemd 或容器编排并配置健康探针）。

## 环境变量

### 开发必需

| 变量 | 本地默认 | 说明 |
|---|---|---|
| `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` | `127.0.0.1:8848` / 空 | 注册与配置中心 |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | 缓存、锁、mock 桥接 |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | 空 | Redis ACL 凭据（生产必须注入） |
| `OTS_MODE` | dev 下 `simulation` | `simulation`（仅 dev/test）或 `ots` |
| `DOC_MQ_MODE` | `mock` | `mock`（仅 dev/test）或 `rocketmq` |
| `SERVER_PORT` | 8080/8081/8082 | 各服务端口覆盖 |

### 生产/真实中间件

| 变量 | 说明 |
|---|---|
| `OTS_ENDPOINT` / `OTS_INSTANCE` | Tablestore 接入点与实例名 |
| `OTS_ACCESS_KEY_ID` / `OTS_ACCESS_KEY_SECRET` | Tablestore 凭据 |
| `OTS_AUTO_CREATE` | 启动自动建表（生产必须为 false） |
| `ROCKETMQ_ENDPOINT` / `ROCKETMQ_ACCESS_KEY` / `ROCKETMQ_SECRET_KEY` | RocketMQ 接入配置 |
| `GATEWAY_JWT_SECRET` | 网关 JWT 签名密钥（HS256，生产要求 ≥32 字节） |
| `GATEWAY_JWT_ISSUER` / `GATEWAY_JWT_AUDIENCE` | 可选强校验；配置后不匹配的令牌被拒绝 |
| `GATEWAY_TRUSTED_PROXIES` | 可信反向代理地址（逗号分隔；仅这些来源的 XFF 被采信） |
| `GATEWAY_AUTH_ENABLED` | 生产必须为 true；dev profile 默认 false |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | 网关允许的来源白名单（禁止 `*` + credentials） |
| `SENTINEL_DASHBOARD` | Sentinel 控制台地址（仅监控；规则以 Nacos 为准） |

### 调优（可选，均有默认值）

`DOC_CACHE_L1_TTL_MINUTES`（60）、`DOC_CACHE_L2_TTL_MINUTES`（5）、`DOC_CACHE_WARMUP_ENABLED`（true）、`DOC_SCHEMA_INVALIDATION_MQ`（true）、`RECONCILE_UNIQUE_ORPHAN`（false）、`REDIS_TIMEOUT_MS`（2000ms）、`REDIS_CONNECT_TIMEOUT_MS`（2000ms）。

> 生产启动校验：prod profile 下禁止 simulation、mock 消息、启动自动建表；网关必须开启认证并使用强密钥。配置不满足时服务 fail-fast，不会静默降级。

## 已知边界与验证状态

- 规则引擎当前只做校验，不提供金额计算、赋值或字段联动；`unique` 约束当前仅对单据头字段生效，明细子字段的唯一范围未定义；
- 主行业务变更与不可变操作日志行在同一 OTS 局部事务内提交；历史、事件、审计由持久日志幂等投影，允许短时滞后。投影连续失败 8 次后转人工状态，源日志保留，不会伪造已成功的投影；
- 发布新增可检索字段采用“`{index}_reindex` 兄弟候选索引 → 全量同步 → `switchIndexName` 切流 → 保留旧索引”。发布请求在候选未就绪时失败并保持草稿态，旧索引持续在线；旧索引清理默认关闭，需显式开启并经过回滚窗口；OTS 同一时刻只保留一个兄弟回滚位；
- 多元索引为异步构建，写入后“立即按列表条件检索”可能短暂不可见，详情点查不受影响；
- 事件按单据 `event_seq` + checkpoint 顺序投递，失败退避时不越序，退避耗尽会阻断该单据序列并告警；发送成功但确认前崩溃会重发同一 `event_id`（at-least-once），消费方必须幂等；
- 升级前已存在的 Outbox 行没有 `event_seq`，需按迁移窗口清理或人工重放后再启用顺序投递；
- 已有实例升级必须先执行 V2 bootstrap（新增 `event_delivery_cursor`、`index_publish_task` 及两个增量索引）；生产环境不得依赖启动自动建表，应由发布流水线显式执行；
- 多租户、复杂审批、打印模板、Excel 批量导入导出不在当前范围；
- [性能基线](doc-platform-parent/docs/perf-report.md) 来自本地仿真环境，**不代表生产容量**；真实 OTS/RocketMQ/Redis 环境需重新压测；
- 本轮已在真实 OTS 测试实例完成操作日志事务、投影重放、顺序投递、Saga 崩溃后双实例恢复、在线索引切流验证；浏览器端 E2E 仍需按部署环境执行。

## 接口与开发文档

- [提交前审核清单](doc-platform-parent/docs/commit-review-checklist.md)
- [OpenAPI 快照](doc-platform-parent/docs/openapi/)
- [告警规则](doc-platform-parent/docs/alert-rules.md)
- [仿真性能报告](doc-platform-parent/docs/perf-report.md)

## 贡献

欢迎 Issue 与 Pull Request。提交前必须通过[提交前审核清单](doc-platform-parent/docs/commit-review-checklist.md)（黑名单路径、敏感串零容忍、体积抽查）；后端遵循 Spring Cloud Alibaba 规范，前端遵循 Vue 规范。

## 许可证

本项目基于 **GPL-3.0-or-later** 开源，完整文本见 [LICENSE](LICENSE)。

Copyright © 2026 Document-Driven Platform contributors.
