# 架构与实现总览 · AI 面试陪练服务

> 一套「AI 面试陪练」后端能力，用 **Python / Java / .NET** 各实现一遍，**对外 REST 契约完全一致**。
> 本文说明：整体架构、共享接口契约、真实评测结果、三语言各自的工程亮点、如何本地运行。

---

## 1. 它解决什么

一个「主动回忆式」技术面试陪练服务：给定知识点，**检索知识库 → 出题 → 评估回答 → 追问 / 给学习建议**。
知识内容来自一个公开、版本化的内容源 [`mianshi-zhilian-content`](https://github.com/nontracey/mianshi-zhilian-content)（16 领域、429 个 production 知识点，含讲解卡片、主动回忆题、评分标准 rubric）。

配套旗舰产品「面试智练」（Flutter 多端，已上线）里的 AI 评估是**客户端直连模型**做的；本仓库把它重做成**服务端完整 RAG + Agent** 能力，并证明这套能力**不绑语言**。

---

## 2. 统一架构（三语言同构）

```
                 ┌──────────────── 同一套 REST 契约 ────────────────┐
   HTTP 客户端 → │  /api/ingest   /api/ask   /api/interview/*        │
                 │  /api/agent/session   /health   /api/metrics      │
                 └───────────────────────┬──────────────────────────┘
                                          │
   ┌──────────────────────────────────────────────────────────────┐
   │  RAG 链路   loader(manifest驱动) → splitter → embedder          │
   │             → vector store → 混合检索(向量+BM25+RRF+可选rerank)  │
   │             → generator(防幻觉 Prompt：仅依据上下文/标注来源)     │
   │  面试        出题(取知识库 recallPrompts) · LLM-as-judge 评估    │
   │             (按知识点 rubric 打分, temperature=0 可复现)         │
   │  Agent       检索 → 出题 → 模拟答 → 评估 → 条件追问 / 学习建议    │
   │             (Function Calling 工具: 检索/取评分标准/记笔记)      │
   │  工程化      SSE 流式 · 语义缓存 · 输入护栏(注入检测/PII脱敏)     │
   │             · 可观测(traceId / token / 命中率 / 延迟)            │
   └──────────────────────────────────────────────────────────────┘
```

**统一响应信封** `ApiResponse<T>`：`{ code, message, data, traceId }`（三语言一致）。
**统一数据源接入**：manifest 驱动，`KB_CONTENT_URL`（公开源，默认）/ `KB_CONTENT_PATH`（本地）/ 样例数据三层降级，与线上 App 消费同一版本化内容源。

---

## 3. 共享 REST 契约

| 方法 | 路径 | 作用 |
|------|------|------|
| POST | `/api/ingest` | 加载知识库 → 切分 → 向量化 → 入库 + 建 BM25 索引 |
| POST | `/api/ask` | RAG 问答（混合检索 + 防幻觉生成，带来源；支持 SSE 流式） |
| POST | `/api/interview/question` | 按知识点出题（取自人工撰写的主动回忆题） |
| POST | `/api/interview/evaluate` | LLM-as-judge 按 rubric 结构化评分（温度 0） |
| POST | `/api/agent/session` | Agent 模拟面试：检索→出题→评估→条件追问 |
| GET | `/health` · `/api/metrics` | 健康检查 / 累计指标 |

---

## 4. 真实评测结果（不是估计，是跑出来的）

**检索质量**：本地 `bge-small-zh-v1.5` 编码，429 知识点语料，两组评测集对比三种检索模式：

| 评测集 | 纯向量 hit_rate | 混合(向量+BM25+RRF) hit_rate |
|--------|----------------|------------------------------|
| 常规集（30 题，语义清晰的问答） | 1.00（MRR 0.98） | 1.00（持平） |
| 难集（15 题，用户口语化/关键词化查询） | **0.87** | **1.00**（MRR 0.83→0.88） |

**结论（诚实呈现）**：语义清晰的问题上纯向量已经足够；但面对**用户真实敲入的短关键词/换词查询**，纯向量会漏，混合检索靠 BM25 把召回从 **87% 拉回 100%**。混合检索的价值**取决于查询类型**——这是用两组评测集测出来的边界，不是拍脑袋。
（复现见 [`python-rag-agent/benchmarks/report.md`](../python-rag-agent/benchmarks/report.md) 与 `report_hard.md`。指标为自实现的 hit_rate / MRR / context_coverage；RAGAS 库的生成质量指标尚未接入。）

**Agent 行为观察**：真跑发现「同一个模型既当考生又当考官」会自评高分、使多轮追问难以触发——真实产品里考生是人类用户，此问题不存在；这是理解系统边界的一个例子。

---

## 5. 三语言各自的工程亮点

| | 栈 | 亮点 |
|---|---|---|
| **python-rag-agent** | FastAPI · LangGraph · sentence-transformers | 主项目，全链路 + 评测脚本 + Streamlit demo，60 单测；可切本地 embedding / API |
| **java-ai-service** | Spring Boot 3 · Spring AI · WebFlux | `ChatClient` + `QuestionAnswerAdvisor` 注入检索、`BeanOutputConverter` 强类型评估、`Flux<ServerSentEvent>` 流式；**纯原生、零第三方业务框架** |
| **dotnet-ai-service** | .NET 8 · Minimal API | 同契约的 C# 实现，`ApiResponse<T>` 信封 + traceId 中间件；三语言收口 |

三个实现对外接口一致，内部体现不同生态下的 RAG/Agent 工程实践——**团队用什么栈都能落地**。

---

## 6. 本地跑起来

三个服务都走 **OpenAI 兼容接口**（`base_url` + `api_key` 可配，支持通义/智谱/DeepSeek/OpenAI/本地模型）。

```bash
# 项目 B（Python）
cd python-rag-agent && cp .env.example .env      # 填 OpenAI 兼容 Key
uv sync && uv run uvicorn app.main:app --reload   # http://127.0.0.1:8000/docs
uv run python benchmarks/run_ragas.py --compare   # 复现检索评测

# 项目 C（Java）
cd java-ai-service && ./gradlew bootRun            # http://localhost:8080/docs (Swagger)

# 项目 D（.NET）
cd dotnet-ai-service && dotnet run                 # http://localhost:5080/health
```

> **零成本本地运行**：embedding 可用本地 `bge-small-zh-v1.5`（sentence-transformers，免费离线），无需付费 embedding API。C/D 亦可通过一个本地 OpenAI 兼容网关复用同一本地 embedding。

---

## 7. 状态与诚实边界

- **B**：全链路 + 评测 + 单测完成，评测数字真实可复现。
- **C / D**：构建通过、服务启动、`/health` 与完整 RAG（ingest→检索→生成→引用来源）**端到端跑通**；rerank 为可选增强，暂以向量+BM25+RRF 为主。
