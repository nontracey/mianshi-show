# 能力矩阵与测试基线

状态定义：

- **已验证**：相关命令已在当前机器成功执行。
- **已实现，未做基础设施验证**：源码和测试入口已存在，但当前未配置或启动外部环境。
- **待完成**：对应能力尚未完整实现。

| 能力 | Java | Python | .NET |
|---|---|---|---|
| 稳定框架版本 | 已验证：Boot 3.5.16 / Spring AI 1.1.8 | 已验证：LangGraph 1.2.11 | 已验证：Semantic Kernel 1.79.0 |
| Agent 框架 | `ToolCallback` + 显式状态机 | 已验证：`StateGraph`、类型化状态、条件边 | Semantic Kernel `KernelFunction` / Function Choice |
| Checkpoint | 待完成 | 已验证内存 checkpoint；PostgreSQL Saver 待完成 | 待完成 |
| pgvector 实现 | 已实现：Spring `PgVectorStore` | 已实现：psycopg/pgvector | 已实现：Npgsql/Pgvector |
| pgvector 测试 | Testcontainers 测试已编写，当前未配置 Docker | 容器集成测试待补充 | pgvector 容器集成测试待补充 |
| 可信租户链 | 凭证映射及异步上下文测试 | 凭证映射、ContextVar 和隔离测试 | 凭证映射及 Repository 隔离测试 |
| Redis 缓存 | 已引入依赖；旧缓存仍为内存实现 | 旧缓存仍为内存实现 | 已引入依赖；旧缓存仍为内存实现 |
| 混合检索与 RRF | 已有单元测试 | 已有单元测试并验证 | Repository 租户测试已验证 |
| 完整 Citation v1 | 待完成：旧 `Source` 字段不完整 | 待完成：旧 `Source` 字段不完整 | 待完成：旧来源字段不完整 |
| SSE 取消 | 部分完成：worker 可中断 | 已实现异步取消及上下文传播 | 已实现有界流和 `RequestAborted` |
| OpenTelemetry | 已有 Actuator/Micrometer；OTLP 待完成 | 待完成 | 待完成 |
| MCP Server/Client | 待完成 | 待完成 | 待完成 |
| 增量入库 | 已有数据库 Schema；应用服务待完成 | 待完成 | 待完成 |
| 在线跨语言契约测试 | 当前仅验证共享 Schema 和文档哈希 | 同左 | 同左 |

## 改造前基线

- Java：`./gradlew test` 退出码为 0，但结果是 `NO-SOURCE`，不存在测试源码。
- Python：49 个测试通过、6 个失败、4 个初始化错误。
- .NET：`dotnet test` 退出码为 0，但不存在测试项目，不能视为测试通过。
- 当前机器未安装 Docker。

## 当前验证结果

- `python3 scripts/contract_smoke.py`：共享清单和 SHA-256 校验通过，退出码 0。
- `cd java-ai-service && ./gradlew test --console=plain`：
  共发现 7 个测试，6 个通过，1 个依赖 Docker 的 pgvector 测试跳过，退出码 0。
- `cd python-rag-agent && uv run ruff check app tests && uv run pytest -q`：
  静态检查通过；64 个测试通过，存在 1 条第三方依赖弃用警告，退出码 0。
- `dotnet test dotnet-ai-service.Tests/DotnetAiService.Tests.csproj --no-restore --nologo`：
  3 个测试通过，0 个失败，0 个跳过，退出码 0。
- `cd dotnet-ai-service && dotnet build --no-restore --nologo`：
  0 个警告，0 个错误，退出码 0。

编译成功只能证明源码和依赖关系正确，不能据此宣称 pgvector、Redis 或模型服务已经完成运行时验收。
