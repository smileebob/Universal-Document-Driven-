# doc-gateway

API 网关（doc-gateway，端口 8080，WebFlux 响应式）：唯一外部入口，路由、JWT 认证、限流、审计埋点（A-07，GW 系列），无持久化，禁连 Tablestore。

路由：/api/v1/{schema,rule,menu}/** → lb://meta-service；/api/v1/{data,query,audit,saga,event}/** → lb://runtime-service；/api/v1/internal/** 不配路由（仅内网 Feign 直达）。
