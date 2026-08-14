# mianshi-show

同一条“版本化知识入库 → 混合检索 → 引用回答 → 面试 Agent → SSE”业务主线的
Java、Python 和 .NET 实现。Java / Spring AI 是主实现；三种语言共享
`data/knowledge-manifest.json`、`contracts/` 与 `db/migration/`。

本仓库不再声称三种实现已全部通过真实基础设施验收。当前机器没有 Docker，
因此 pgvector/Redis Testcontainers 与 Compose 演示只能在有 Docker 的环境执行。
准确状态、命令与缺口见 `docs/CAPABILITY_MATRIX.md`。

## 快速验证

```bash
python3 scripts/contract_smoke.py
cd java-ai-service && ./gradlew test
cd ../python-rag-agent && uv sync --extra dev --locked
uv run ruff check app tests && uv run pytest -q
cd ../dotnet-ai-service && dotnet build
```

## 基础设施

```bash
docker compose up -d postgres redis
```

pgvector 模式必须显式配置数据库连接；配置错误会启动失败，不会静默回落内存。
默认 memory 模式只用于本地演示与确定性测试。密钥只从环境变量或本地配置注入。

## 目录

- `contracts/`：版本化 API、引用、错误与 SSE 事件 Schema。
- `data/knowledge-manifest.json`：唯一共享 manifest，含内容 SHA-256。
- `db/migration/`：入库批次、版本化文档和工具审计表。
- `java-ai-service/`：Spring Boot 3.5.16 + Spring AI 1.1.8。
- `python-rag-agent/`：FastAPI + LangGraph 1.2.11。
- `dotnet-ai-service/`：.NET 8 + Semantic Kernel 1.79.0。
