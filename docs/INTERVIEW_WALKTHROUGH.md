# 15 分钟代码走读

## Java 走读路径

`build.gradle` → `AiConfig` → `VectorStoreService` → `HybridRetriever` →
`AgentOrchestrator` → `TenantFilter` → pgvector 集成测试。

重点说明：

- 为什么在当前 Java 17 环境选择 Spring AI 1.1 稳定版本线。
- memory 与 pgvector 如何通过条件装配明确切换。
- 为什么 pgvector 配置异常必须启动失败，而不能静默降级。
- 租户上下文如何显式传播到 Agent 异步 worker。

## Python 走读路径

`agent/state.py` → `agent/graph.py` → `infra/tenant.py` →
`rag/store.py` → Agent SSE 路由 → 企业契约测试。

重点说明：

- `StateGraph` 的节点、条件边和类型化状态。
- `thread_id` 为什么必须包含可信租户。
- ContextVar 如何跨 FastAPI 和 LangGraph 异步任务传播。
- 当前使用内存 checkpoint 的适用边界，以及后续切换 PostgreSQL Saver 的方式。

## .NET 走读路径

Semantic Kernel 注册 → `AgentPlugin` → `AgentService.RunStreamAsync` 有界 Channel →
`VectorRepository.cs` → API 取消传播 → Repository 单元测试。

重点说明：

- `KernelFunction` 如何生成工具参数 Schema 并参与 Function Calling。
- 为什么使用有界 Channel 控制 SSE 背压。
- `RequestAborted` 如何传播到 Semantic Kernel 模型调用。
- memory 与 Npgsql/Pgvector Repository 如何通过配置切换。

## 常见追问

### 为什么使用 RRF？

向量相似度和关键词检索分数没有统一量纲，直接相加难以解释。RRF 只融合各检索路线的排名，
避免对不可比的原始分数进行错误加权。

### 为什么不能直接相信客户端 Tenant Header？

客户端可以伪造租户标识，直接信任会形成越权和 IDOR 风险。服务应从经过认证的凭证映射可信租户。

### 为什么 pgvector 配置错误时不降级内存？

静默降级会让生产配置表面可用、实际丢失持久化和隔离保障，容易产生数据丢失和错误的上线判断。

### 为什么没有性能数字？

仓库尚未提交统一环境下的可复现负载运行结果，因此不预写 QPS、延迟或吞吐量数字。
