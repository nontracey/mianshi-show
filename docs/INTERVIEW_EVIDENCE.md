# 面试与项目展示证据

## 当前可以陈述的内容

| 可陈述内容 | 实现与验证证据 |
|---|---|
| Python 使用真实 LangGraph | `app/agent/graph.py`；`test_agent_is_real_compiled_langgraph` |
| LangGraph checkpoint 能保存工作流终态 | `test_langgraph_checkpoint_retains_completed_state` |
| 租户身份由凭证映射产生 | 三种语言的 Tenant Middleware/Filter 及对应单元测试 |
| 三种语言复用同一份版本化知识清单 | `data/knowledge-manifest.json` 和 `scripts/contract_smoke.py` |
| Java pgvector 模式使用 Spring `PgVectorStore` | 条件装配代码和 Testcontainers 测试源码 |
| Python pgvector 配置错误时不会静默降级 | `test_pgvector_configuration_fails_fast_without_connection` |
| .NET Agent 支持流式取消 | `RunStreamAsync` 和 `RequestAborted` 传播代码 |
| .NET 向量 Repository 按租户隔离 | `VectorRepositoryTests.QueryAndResetAreTenantScoped` |

## 补充证据后才可陈述

- 三种语言均已连接真实 pgvector 和 Redis 并通过集成测试。
- LangGraph 已使用 PostgreSQL checkpoint 完成跨进程恢复。
- 三个 HTTP 服务通过同一组在线 REST/SSE 契约测试。
- 在冻结评测集上得到可复现的 Recall、MRR、nDCG 和引用正确性结果。
- 完成并记录 SSE 并发、客户端断连、队列饱和和依赖故障测试。

## 禁止陈述

- 禁止声称三种企业级实现已经全部完成。
- 禁止声称当前机器上所有 Testcontainers 测试均已通过。
- 禁止引用没有原始运行结果支撑的 QPS、延迟、成本、准确率、覆盖率或生产用户量。
- 禁止声称 PostgreSQL LangGraph checkpoint、Redis 全量缓存或 MCP 已经完成。
