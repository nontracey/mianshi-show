# dotnet-ai-service · 项目 D

> AI 面试陪练服务(.NET 版)。与 B/C **对外契约完全一致**,三语言收口。
> 加分项:展示同一套 AI 能力能落到 .NET 生态。

## 技术栈

| 层 | 选型 |
|----|------|
| 运行时 | .NET 8(LTS) |
| Web | ASP.NET Core Minimal API |
| AI | HttpClient 直调 OpenAI 兼容 API(与 B 的 openai SDK 等价;可平滑替换为 Semantic Kernel) |
| 向量库 | 内存(纯 C# cosine) |
| 混合检索 | 纯 C# BM25 + 向量 + RRF 融合 |
| 文档 | Swashbuckle(Swagger UI) |

> **设计取舍**:为避免 NuGet 依赖下载阻塞,LLM 调用用内置 HttpClient 直调 OpenAI 兼容 API,接口与 Semantic Kernel 等价(`IChatCompletionService` / `ITextEmbeddingGenerationService` 可平滑替换)。向量库与 B/C 同构(内存版,零依赖)。

## 目录结构

```
dotnet-ai-service/
├── DotnetAiService.csproj
├── Program.cs              Minimal API 入口 + 端点注册 + DI
├── appsettings.json
├── .env.example
├── Common/                 ApiResponse<T> AppOptions TraceIdMiddleware
└── Services/               LlmClient KnowledgeBase RagService(含 InterviewService)
```

## 快速开始

```bash
cd dotnet-ai-service
cp .env.example .env        # 或在 appsettings.json 填 OpenAI 配置
dotnet run
# 打开 http://localhost:8090/swagger 看 Swagger UI
# /health 验证;POST /api/ingest;POST /api/ask
```

## 里程碑与验收

### 阶段三(一次性建到契约齐全)
- [x] 契约对齐 §4:`/health /api/ingest /api/ask /api/interview/question /api/interview/evaluate /api/metrics` 全实现
- [x] `ApiResponse<T>` 统一封套 + traceId 中间件(AsyncLocal)
- [x] LLM 从 env/appsettings 读 OpenAI 兼容配置,可切通义/DeepSeek
- [x] RAG 问答带来源;混合检索(向量+BM25+RRF)可用
- [x] `InterviewService.EvaluateAsync` 温度 0 + 强类型反序列化,可复现
- [x] Swagger UI 可交互
- [ ] `/api/agent/session` SSE 流式(待补;Agent 编排逻辑与 B/C 同构)
- [ ] 限流中间件 + 自带 Key 头(待补)
- [ ] Docker + HF Spaces 部署

## 验收自检

- `appsettings.json` 内无真实密钥;机密走环境变量或 `appsettings.Local.json`(已 gitignore)
- Swagger 六接口与 B/C 同名同义

## 与 B/C 的能力对照

| 能力 | B(Python) | C(Java) | D(.NET) |
|------|-----------|----------|---------|
| LLM | openai SDK | Spring AI ChatClient | HttpClient 直调 |
| Embedding | openai | EmbeddingModel | HttpClient 直调 |
| 向量库 | InMemory | InMemory | InMemory(同接口) |
| 检索 | BM25+RRF | BM25+RRF | BM25+RRF |
| 评估 | temperature=0 JSON | temperature=0 record | temperature=0 JSON |
| Agent | 状态机+Function Calling | 状态机+工具 | 待补 |
| SSE | sse-starlette | SseEmitter | 待补 |
| 观测 | traceId ContextVar | traceId MDC | traceId AsyncLocal |

## 三语言收口说明

B/C/D 三语言实现同一套「AI 面试陪练」契约(§4),证明这套 AI 能力能落到任何团队的技术栈:
- **Python**:主力,完整 RAG + Agent + 工程化 + 评测(M1-M6 全完成,60 测试)
- **Java**:差异化,原生 Spring AI,Java 护城河(阶段一/二完成,编译通过)
- **.NET**:加分,Minimal API + HttpClient 直调(契约齐全,Agent 待补)
