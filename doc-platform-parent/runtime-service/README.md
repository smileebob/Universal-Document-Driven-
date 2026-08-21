# runtime-service

运行域微服务（runtime-service，端口 8082）：数据 CRUD、五类校验、状态流转（锁+规则+Saga）、统一查询、Outbox 事件、审计、运营监控。

分层：controller（接入层）→ service（应用服务层）→ domain（业务核心层）← infrastructure（基础设施层），对外 Feign 契约独立于 runtime-service-api 模块（A-04）。
