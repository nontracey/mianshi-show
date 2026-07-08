# java-ai-service · 项目 C

> AI 面试陪练服务(Java 版)。与 B/D **对外契约完全一致**,展示 Java 生态(Spring AI)下的 RAG/Agent 工程。
> 10 年 Java 底子的护城河。

## 合规红线

- ❌ 绝不使用公司专有框架 msf4spring 或任何公司代码/依赖
- ✅ 只用开源原生栈:Spring Boot 3.4 + Spring AI
- 面试话术:**"我研究过企业级 Java AI 平台的架构,用原生 Spring AI 自己重写了一套"**

## 技术栈

| 层 | 选型 |
|----|------|
| 语言 | Java 17(LTS) |
| 框架 | Spring Boot 3.4 + Spring AI 1.0.0-M4 |
| AI | Spring AI(OpenAI 兼容,可切通义/DeepSeek) |
| 向量库 | InMemory(默认)/ pgvector(prod) |
| 混合检索 | 纯 Java BM25 + 向量 + RRF 融合 |
| 文档 | springdoc-openapi(Swagger UI) |
| SSE | SseEmitter(MVC) |
| 构建 | Gradle |

## 目录结构

```
java-ai-service/
├── build.gradle / settings.gradle
├── .env.example
├── src/main/java/com/nontracey/aiservice/
│   ├── AiServiceApplication.java
│   ├── config/         AiConfig(ChatClient/EmbeddingModel) AppProperties
│   ├── common/         ApiResponse<T> GlobalExceptionHandler TraceIdFilter
│   ├── dto/            Dtos(record 请求/响应) StreamEvent
│   ├── api/            OpsController RagController InterviewController AgentController
│   ├── rag/            Loader Splitter VectorStoreService HybridRetriever Generator
│   ├── interview/      QuestionService EvaluatorService(温度 0)
│   ├── agent/          AgentOrchestrator AgentTools(Function Calling)
│   └── infra/          Metrics Guardrails
├── src/main/resources/application.yml
└── src/test/java/...
```

## 快速开始

```bash
cd java-ai-service
cp .env.example .env       # 填入 OpenAI 兼容的 API Key(或 export 环境变量)
./gradlew bootRun          # 或 gradle bootRun
# 打开 http://127.0.0.1:8080/docs 看 Swagger UI
# /health 验证;POST /api/ingest;POST /api/ask
```

测试:
```bash
./gradlew test
```

## 里程碑与验收

### 阶段一(起步)✅
- [x] `GET /health` + `POST /api/ask`(RAG 问答,带来源)
- [x] `ChatClient` 从环境变量读 OpenAI 兼容配置
- [x] Agent 工具(`search_knowledge` / `get_scoring_rubric` / `save_note`)被编排调用

### 阶段二(扩成完整服务)✅
- [x] 契约对齐 §4:`/ingest /ask /interview/question /interview/evaluate /agent/session /metrics` 全实现
- [x] `EvaluatorService` 温度 0 + 强类型 record 评估,可复现
- [x] 混合检索(向量+BM25+RRF)可用
- [x] `/agent/session` 用 SseEmitter 流式推进
- [x] `ApiResponse<T>` 统一封套 + `GlobalExceptionHandler` + traceId 日志(MDC)
- [x] Swagger UI 可交互(`/docs`)

### 阶段三(加固,部分)
- [x] 限流基础(中间件占位;完整滑动窗口待补)
- [x] 多租户:`X-Tenant-Id` 隔离(占位,知识库集合隔离待补)
- [ ] pgvector 生产向量库(待接入)
- [ ] Docker 镜像 + Render/HF Spaces 部署

## 验收自检

```bash
git grep -i msf4spring          # 必须 0 结果(合规红线)
git grep -iE "api[_-]?key\s*=\s*['\"]"  # 无硬编码密钥
```

## 架构图(与 B 同构)

```
请求 -> TraceIdFilter(MDC) -> Controller -> Service
  /ask -> Guardrails(注入检测) -> HybridRetriever(向量+BM25+RRF) -> Generator(ChatClient) -> {answer, sources}
  /evaluate -> EvaluatorService(温度 0,JSON 强类型) -> Evaluation
  /agent/session(SseEmitter) -> AgentOrchestrator:
      retrieve(search_knowledge tool) -> ask -> simulate -> evaluate -> decide -> followup/advise -> done
```

## 与 B 的能力对照

| 能力 | B(Python) | C(Java) |
|------|-----------|----------|
| LLM | openai SDK | Spring AI ChatClient |
| Embedding | openai | EmbeddingModel |
| 向量库 | InMemory/Chroma | InMemory(同接口) |
| 检索 | rank-bm25 + RRF | 纯 Java BM25 + RRF |
| 评估 | temperature=0 JSON | temperature=0 + record 强类型 |
| Agent | 自实现状态机 | 自实现状态机(同构) |
| SSE | sse-starlette | SseEmitter |
| 观测 | traceId ContextVar | traceId MDC |

## 在线链接

- 待部署(Render/HF Spaces)
