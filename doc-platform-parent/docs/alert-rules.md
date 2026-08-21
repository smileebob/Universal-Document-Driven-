# 告警规则清单（alert-rules）

> 任务 #20（步骤 41 可观测性）产出。依据：design/04 §5.1（九项容量预警阈值）/§5.2（doc_data 分表预案）/§2.3（hash 升位判据）、design/11 §4.2（Prometheus 指标清单）/§4.4（告警规则表 AL/MQ/SG/SV）、design/07 §4.6、prd/13（性能指标即 SLA 线）。
>
> 分级约定：**红（P0 立即处置，15 分钟响应）/ 黄（P1 工作时间处置）**，全量接入值班通道（design/11 §5.1）。

---

## 一、指标来源与命名映射

应用侧指标经 micrometer 打点，三服务各自暴露 `/actuator/prometheus`（不经网关路由，J-04）。

| 设计名（design/11 §4.2） | 实现名（本仓库） | 类型 | 说明 |
|--------------------------|------------------|------|------|
| — | `ddp_tablestore_operation_seconds{operation}` | Timer（P95/P99） | TablestoreTemplate 操作延迟直方图（get_row/put_row/update_row/delete_row/batch_get/batch_write/get_range/get_partition_range/search/local_transaction） |
| — | `ddp_tablestore_operation_total{operation,result}` | Counter | 操作计数，result=success/error |
| — | `ddp_cache_get_total{layer=l2\|l1,result=hit\|miss}` | Counter | meta 配置缓存 L2 Caffeine / L1 Redis 命中率 |
| `ddp_outbox_pending` | `ddp_outbox_pending` | Gauge | outbox 待投递行数（最近扫描 send_status=0 可见行 + 即时队列深度） |
| — | `ddp_outbox_relay_total{result=delivered\|failed\|manual}` | Counter | 中继投递结果；`manual` 即 send_status=2 转人工（对应设计 `ddp_outbox_failed_total` / MQ-02） |
| `ddp_saga_dead_letter_new` | `ddp_saga_dead_letter_total` | Counter | Saga 冲正失败死信新增 |
| — | `ddp_saga_execution_total{result=completed\|compensated\|dead}` | Counter | Saga 执行终态计数 |
| `ddp_transition_total{result}` | `ddp_transition_total{result=success\|failed}` | Counter | 状态流转成功/失败 |
| 黄金指标 | `http_server_requests_seconds`（Spring 自动） | Timer | QPS/延迟/错误率按 URI |

> 命名差异登记：设计文档 `ddp_outbox_failed_total`、`ddp_saga_dead_letter_new` 为实现口径 `ddp_outbox_relay_total{result=manual}`、`ddp_saga_dead_letter_total` 的示意名，语义一致；如后续对齐设计名，仅需改 Counter 注册名与本文档。

---

## 二、Prometheus 告警规则（应用侧可直接表达项）

以下为 Prometheus rules YAML 片段，job 标签按部署实际（`gateway`/`meta-service`/`runtime-service`）调整：

```yaml
groups:
  - name: ddp-runtime
    rules:
      # MQ-01 / AL-07（应用侧近似）：outbox 滞留持续偏高 → 检查中继线程池与 RocketMQ 连通性
      # 精确口径（send_status=0 且 create_time 超 10min 行数）由 event_outbox_index 定时扫描任务产出，
      # 云监控按 AL-07 黄>100 / 红>1000 配置；Gauge 为应用侧先行观测值
      - alert: DdpOutboxPendingHigh
        expr: ddp_outbox_pending > 100
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "outbox 待投递行数 > 100 持续 10 分钟（AL-07 黄）"
      - alert: DdpOutboxPendingCritical
        expr: ddp_outbox_pending > 1000
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "outbox 待投递行数 > 1000（AL-07 红）：立即检查中继与 MQ 连通性"

      # MQ-02 / EV-02：send_status=2 转人工新增即告警（处置入口：P-OP-03 事件重发监控页）
      - alert: DdpOutboxManualNew
        expr: increase(ddp_outbox_relay_total{result="manual"}[5m]) > 0
        labels: { severity: critical }
        annotations:
          summary: "事件退避耗尽转人工（send_status=2 新增）：P-OP-03 页人工重发"

      # SG-01 / TX-03：Saga 冲正失败死信新增即告警（处置入口：P-OP-02 补偿失败处理页）
      - alert: DdpSagaDeadLetterNew
        expr: increase(ddp_saga_dead_letter_total[5m]) > 0
        labels: { severity: critical }
        annotations:
          summary: "Saga 冲正失败转死信新增：P-OP-02 页重试冲正/线下处理留痕"

      # SV-02：流转 P95 > 3s（prd/13 指标即 SLA 线）
      - alert: DdpTransitionLatencyHigh
        expr: |
          histogram_quantile(0.95,
            sum(rate(http_server_requests_seconds_bucket{uri=~".*/transition"}[5m])) by (le)) > 3
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "流转 P95 > 3s（prd/13 SLA）：对照 01 号第五节逐层排查"

      # SV-02：列表查询 P95 > 2s
      - alert: DdpQueryLatencyHigh
        expr: |
          histogram_quantile(0.95,
            sum(rate(http_server_requests_seconds_bucket{uri=~".*/query.*"}[5m])) by (le)) > 2
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "列表查询 P95 > 2s（prd/13 SLA）"

      # SV-01：5xx 错误率 > 1% 持续 5 分钟
      - alert: DdpHttp5xxRateHigh
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
            / sum(rate(http_server_requests_seconds_count[5m])) > 0.01
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "5xx 错误率 > 1% 持续 5 分钟（SV-01）"

      # 流转成功率突降（design/11 §4.2 联动）：5 分钟成功率 < 95%
      - alert: DdpTransitionSuccessRateLow
        expr: |
          sum(rate(ddp_transition_total{result="success"}[5m]))
            / (sum(rate(ddp_transition_total[5m])) > 0) < 0.95
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "状态流转成功率 < 95%：排查规则求值/锁竞争/下游"

      # Tablestore 防腐层错误率突增（J-03）：5 分钟 error 占比 > 1%
      - alert: DdpTablestoreErrorRateHigh
        expr: |
          sum(rate(ddp_tablestore_operation_total{result="error"}[5m]))
            / (sum(rate(ddp_tablestore_operation_total[5m])) > 0) > 0.01
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "Tablestore 操作错误率 > 1%：检查 OTS 实例/限流/网络"

      # 缓存命中率劣化（06 号）：L1+L2 综合命中率 < 70% 持续 10 分钟（配置读应高命中）
      - alert: DdpCacheHitRateLow
        expr: |
          sum(rate(ddp_cache_get_total{result="hit"}[10m]))
            / (sum(rate(ddp_cache_get_total[10m])) > 0) < 0.70
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "配置缓存综合命中率 < 70%：检查广播失效链路/回源风暴"

      # SV-05：局部事务延迟劣化（目标 < 200ms，红线 > 5s；以 local_transaction P99 近似）
      - alert: DdpLocalTransactionSlow
        expr: |
          histogram_quantile(0.99,
            sum(rate(ddp_tablestore_operation_seconds_bucket{operation="local_transaction"}[5m])) by (le)) > 5
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "局部事务 P99 > 5s（SV-05，03 号 6.2 红线 1）"
```

---

## 三、云监控对照说明（托管指标不可由应用侧表达项）

以下告警经**阿里云云监控**（Tablestore / RocketMQ 托管指标）配置，阈值与 design/04 §5.1、design/11 §4.4 一致，转发同一值班通道（design/11 §4.5）：

| # | 维度 | 云监控指标口径 | 黄 | 红 |
|---|------|----------------|----|----|
| AL-01 | 行数增长 | 各表月增量 vs 1.2 基线偏离（多元索引 count 聚合每日任务产出） | > 50% | > 100% |
| AL-02 | doc_data 行数绝对值 | 多元索引 count | > 250 万 | > 300 万 |
| AL-03 | 单分区体积 | Tablestore 分区级指标 | ≥ 8 GB | ≥ 9.5 GB |
| AL-04 | 单分区 QPS 占比 | 分区读写 QPS / 实例总 QPS | > 50% | > 70% |
| AL-05 | 实例 CU/s 持续 5 分钟 | Tablestore CU 指标 | > 3000 | > 4500 |
| AL-06 | 多元索引同步延迟 | Tablestore 索引延迟指标 | > 5s | > 30s |
| AL-07 | Outbox 滞留（精确口径） | event_outbox_index 扫描任务：send_status=0 且 create_time 超 10min 行数 | > 100 | > 1000 |
| AL-08 | 归档健康 | audit_log Tunnel→OSS 交付对账任务缺口行数/日 | > 0 | > 1 万 |
| AL-09 | 大行写入 | 写入侧校验打点（attributes 写前体积；>1.5 MB 硬拒由代码强制） | > 256 KB | > 1.5 MB |
| MQ-03 | 中继扫描命中突增 | 单轮扫描命中数环比突增（下游 MQ 故障征兆） | 趋势告警 | — |
| MQ-04 | MQ 消费死信堆积 | 各消费 Group `%DLQ%{ConsumerGroup}` 堆积量 | > 0 | — |
| MQ-05 | Tunnel 滞后 | Tunnel 消费滞后时长 | > 24h | — |
| MQ-06 | 广播消费失败率 | 缓存失效广播消费失败率（本地重试 3 次后放弃打点） | > 1% | — |
| MQ-07 | 事务回查丢弃 | 半消息回查上限内 outbox 行查不到被 Broker 丢弃（异常信号核对） | 出现即排查 | — |
| SV-03 | 实例离线 | Nacos 实例健康/心跳超时 | 下线即告警 + 自愈拉起 | — |
| SV-04 | Sentinel 熔断触发 | 限流/熔断触发计数 | 触发即记录评估 | — |

> **广播失效丢失容忍说明**（MQ-06 备注）：`schema-invalidation` 广播失败不重投、重启窗口丢失不补投，为设计接受的语义——陈旧数据由 Caffeine TTL ≤ 5min 自然过期兜底（06 号 1.3/3.1）。广播类不设死信告警、不做补发，仅监控失败率；把广播丢失当故障处理属误报。

---

## 四、容量演进预案触发条件登记

### 4.1 hash 4 → 6 位升位（design/04 §2.3(2)，任一命中即立项评估）

| # | 监控指标 | 阈值 |
|---|---------|------|
| 1 | 表累计行数 | > 1000 万行 |
| 2 | 表累计体积 | > 100 GB（索引放大后 > 200 GB） |
| 3 | 单分区体积 | 分裂后仍 ≥ 8 GB 且分裂频率 > 1 次/周 |
| 4 | 分区数 | > 64 个（宜配合分表而非单纯升位） |

实施方式（不可原地变更）：新建 hash6 表 → Tunnel 增量 + 全量批量回放迁移 → 双写期 → 读切换 → 旧表下线（03 号 8.2「主键变更单独立项」红线）。

### 4.2 doc_data 分表 TableNameResolver 预案（design/04 §5.2，任一命中即立项）

| # | 判据 | 阈值 |
|---|------|------|
| 1 | doc_data 总行数 | > 5000 万行 |
| 2 | 单一 doc_code 数据占比 | > 50% 且该单据类型仍高速增长 |
| 3 | 单表多元索引字段预算 | 逼近 65/100 上限且新单据类型仍需独立索引字段 |
| 4 | 单表体积（含索引） | > 1 TB |

预案要点：`TablestoreTemplate` 内置 TableNameResolver 按 `doc_code → domain` 映射（Nacos 下发）解析物理表名，统一查询 DSL target 翻译前先经路由（RD-10 透明）；每 domain 表族含 doc_data / doc_data_history / event_outbox 同构三表 + 各自多元索引；迁移仅头部 doc_code，双写期 ≥ 7 天，读流量按 doc_code 灰度切换，路由映射可分钟级回滚。

> 升位与分表解决不同瓶颈（分区前缀基数 vs 单表体积），立项时按 §2.3 与 §5.2 判据一并裁决，避免重复迁移。

---

## 五、SLS 关键字告警联动（补充面）

按 design/11 §4.5：SLS 对 ERROR 突增、`send_status=2`、`DEAD_LETTER`、`[saga][ALERT]` 等关键字配置告警，与 Prometheus/云监控互补，统一汇入值班通道；必建索引字段 `traceId`/`level`/`service`/`doc_uuid`/`operator` 支持按 trace 闭环排障。
