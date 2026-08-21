# meta-service

配置域微服务（meta-service，端口 8081）：实体/单据定义、状态机、规则库、规则绑定、事务步骤、菜单挂载的管理与发布。

分层：controller（接入层）→ service（应用服务层）→ domain（业务核心层）← infrastructure（基础设施层），对外 Feign 契约独立于 meta-service-api 模块（A-04）。
