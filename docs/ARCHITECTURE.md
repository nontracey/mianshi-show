# 架构设计

## 共享业务语义

仓库内唯一的知识库清单是 `data/knowledge-manifest.json`，对应 Schema 为
`contracts/knowledge-manifest.schema.json`。清单中的每个文档都记录 SHA-256，
用于校验内容完整性和版本一致性。

`contracts/api.schema.json` 统一定义引用、用量、错误模型和有序 SSE 事件。
三种语言可以采用各自生态的实现方式，但应遵循这份共享契约。

生产存储必须按租户隔离。服务不信任客户端直接提交的租户标识，而是通过部署配置，
把不透明的 `X-Api-Key` 映射为可信租户。匿名演示模式统一进入 `default` 租户，
不得用于生产部署。

## 检索与工作流

三种实现均将向量检索和关键词检索分开处理，分别扩大候选集后通过 RRF 融合，
并允许配置可选的 Reranker。生产 pgvector 配置采用启动失败优先策略：
配置缺失或装配失败时显式报错，不允许静默回落到内存存储。

Python 使用真实 LangGraph `StateGraph`，包含类型化状态、显式节点、条件边和
`InMemorySaver` 开发用 checkpoint。Java 使用 Spring AI `ToolCallback` 和显式状态机。
.NET 使用 Semantic Kernel Plugin，并通过有界 Channel 暴露
`IAsyncEnumerable`；客户端取消会传播到模型调用。

## 关键技术决策

- Java 采用 Spring Boot 3.5.16 + Spring AI 1.1.8。这是与当前 Java 17 环境兼容的
  稳定版本线；Spring AI 2.0 对应 Spring Boot 4 / Java 21 开发基线。
- Java 使用 Spring AI `PgVectorStore`，Python 使用 psycopg/pgvector，
  .NET 使用 Npgsql/Pgvector。
- Redis 缓存键应包含租户、知识库及版本、Provider、模型、Prompt/策略版本和查询指纹。
  现有旧缓存尚未全部迁移到 Redis，因此不能宣称 Redis 缓存已经完整落地。
- .NET 生产级 resilience pipeline 和三语言完整 OpenTelemetry 导出仍属于后续工作。

## 基础设施入口

`docker-compose.yml` 用于启动 pgvector 和 Redis。
数据库对象统一定义在 `db/migration/V001__enterprise_rag.sql`。
当前阶段无需启动外部环境；配置连接参数后即可执行对应集成测试。
