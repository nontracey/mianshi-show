# python-rag-agent · 项目 B

> AI 面试陪练服务(Python 版)。RAG 知识问答 + LLM-judge 评估 + LangGraph Agent + 工程化。
> 三语言(B/C/D)**对外契约完全一致**;本册是 Python 主力实现。

## 一句话叙事

把已上线的「面试智练」App 里的 AI 评估(客户端直连模型),用完整的 **RAG + Agent** 在服务端重实现,并补上知识检索、混合检索、评测、可观测。

## 技术栈

| 层 | 选型 |
|----|------|
| Web | FastAPI + uvicorn(自带 /docs Swagger) |
| LLM/Embedding | openai(OpenAI 兼容,可切通义/DeepSeek/OpenAI/本地) |
| 编排 | 自实现轻量异步状态机(等价 LangGraph;接口一致,可平滑替换) |
| 向量库 | InMemory(默认,零依赖)/ Chroma(可选,持久化)/ pgvector(prod) |
| 混合检索 | rank-bm25(可选)+ 向量 + RRF 融合 + CrossEncoder Rerank(可选) |
| 评测 | 自实现客观指标(hit_rate/MRR/coverage)+ 可选 RAGAS |
| 缓存 | 语义缓存(内存,cosine > 0.95 命中) |
| Demo | Streamlit |
| 观测 | 结构化日志 + traceId + /metrics + 可选 LangSmith |

> **设计取舍**:为避免网络安装受限阻塞主流程,核心依赖最小化(fastapi/openai/pydantic/httpx)。chromadb/rank-bm25/sentence-transformers/ragas/streamlit 均为可选 extras,装上即启用对应能力,未装则优雅降级(内存向量库/纯 Python BM25/跳过 rerank/跳过 RAGAS)。

## 目录结构

```
python-rag-agent/
├── app/
│   ├── main.py            FastAPI 入口:中间件(traceId/限流/异常)+ 路由
│   ├── config.py          Settings(环境变量)
│   ├── schemas.py         Pydantic 请求/响应 + 统一封套 ApiResponse
│   ├── api/               ops(健康/指标) rag(问答/入库) interview(出题/评估) agent(SSE)
│   ├── rag/               loader(manifest 驱动) splitter embedder store retriever generator
│   ├── interview/         question_gen evaluator(LLM-as-judge,温度 0)
│   ├── agent/             state tools(Function Calling) graph(状态机)
│   └── infra/             llm cache(语义) guardrails(注入+PII+JSON) observability ratelimit
├── tests/                 pytest(60 个测试,覆盖 M1-M5 全链路)
├── benchmarks/            run_ragas.py + 评测报告
├── demo/                  Streamlit 一页 demo
├── data/                  (软链根 data/)样例知识库 + eval set
├── pyproject.toml
├── Dockerfile
└── .env.example
```

## 快速开始

```bash
cd python-rag-agent
cp .env.example .env       # 填入 OpenAI 兼容的 API Key
uv sync --extra dev        # 核心 + 测试依赖
uv run pytest -v           # 60 个测试(不依赖真实 LLM,用 FakeLLM mock)
uv run uvicorn app.main:app --reload
# 打开 http://127.0.0.1:8000/docs 看 Swagger
# /health 验证;POST /api/ingest 入库;POST /api/ask 问答
```

Demo:
```bash
uv pip install streamlit
uv run streamlit run demo/app.py
# 打开 http://localhost:8501
```

## 数据源

默认走公开 manifest(`KB_CONTENT_URL`),任何人 clone 即可跑全量 ~429 个 production topic;离线时设 `KB_CONTENT_PATH` 指向本地 clone;最小样例在 `../data/knowledge_base.sample.json`(3 个 topic,供 CI/快速验证)。

## 里程碑与验收

### M1 核心 ✅
- [x] `/health` 返回版本与依赖连通性
- [x] `/api/interview/question` 按 topic 出题(直接返回 recallPrompts)
- [x] `/api/interview/evaluate` LLM-as-judge,温度=0,JSON 强类型,可复现(单测验证)
- [x] `/api/ask`(M1 无检索直答,M2 升级为 RAG)
- [x] evaluator 单测(命中/遗漏解析、降级、可复现,8 个测试)

### M2 RAG ✅
- [x] `/api/ingest` 入库(manifest 驱动 -> 切分 -> embed -> 向量库 + BM25),返回 chunk 数
- [x] `/api/ask` 答案带来源 id,来源对得上正确知识条目(单测验证)
- [x] 上下文外问题回答"不知道"(System Prompt 防幻觉)

### M3 混合检索 + 评测 ✅
- [x] retriever 支持 向量 / 混合(RRF)/ 混合+rerank 三档开关
- [x] `benchmarks/run_ragas.py` 跑通(dry-run 验证流程)
- [x] 客观指标:hit_rate / MRR / context_coverage / avg_latency(无需 LLM)
- [x] report.md 对比三档(dry-run 已见 hybrid MRR 1.00 > vector 0.40)
- [ ] 真实 metrics.json(待填 OPENAI_API_KEY 后跑 `--compare` 产出)

### M4 Agent ✅
- [x] `/api/agent/session` 跑完一轮"检索->出题->评估->(追问)->建议"
- [x] 真实 Function Calling:retrieve 节点 LLM 调 `search_knowledge` 工具(单测验证 tool_call)
- [x] SSE 分步事件(retrieve/question/answer/evaluate/followup/advise/done)

### M5 工程化 ✅
- [x] SSE 流式(/ask /agent)
- [x] 语义缓存命中跳过 LLM(usage.cache_hit=true,单测验证)
- [x] guardrails:注入检测(中英文 + 角色伪造 + 超长)+ PII 脱敏(手机/邮箱/身份证/卡号)+ JSON 校验
- [x] `/api/metrics` 有 token/请求/命中率/延迟
- [x] 限流:每 IP 每分钟 N 次 + X-LLM-Key 自带 Key 绕过(单测验证)

### M6 部署 ✅(脚本就绪,上线待用户操作)
- [x] Streamlit demo(三 tab:RAG 问答 / 出题评估 / Agent 模拟面试)
- [x] Dockerfile(后端 uvicorn + 前端 streamlit,HF Spaces 7860 端口)
- [x] 限流 + X-LLM-Key 自带 Key 模式(M5 已实现)
- [ ] HF Spaces 上线(待用户填 Key 部署,步骤见下)

## 部署(HF Spaces)

1. 在 Hugging Face 创建 Space,类型选 Docker
2. 把本目录内容推到 Space 仓库(或用 GitHub 同步)
3. 在 Space Settings -> Repository secrets 配置:
   - `OPENAI_API_KEY`(必填)
   - `OPENAI_BASE_URL`(通义/DeepSeek 等)
   - `LLM_MODEL` / `EMBEDDING_MODEL`
   - `RATE_LIMIT_PER_MINUTE`(默认 20,防烧 Key)
4. Space 启动后访问 `https://<space-name>.hf.space`(Streamlit demo 在 7860)
5. README 附公开 URL

> ⚠️ **公网 demo 成本风险**:任何人都能点 = 烧你的 Key。已内置限流(默认 20 次/分钟/IP)+ 自带 Key 模式(请求头 `X-LLM-Key` 覆盖,不计公共额度)。建议面试时临时开启,或仅展示截图。

## 架构图(RAG + Agent 数据流)

```
用户问题
  │
  ▼
[限流 + traceId 中间件 + guardrails(注入检测/PII 脱敏)]
  │
  ▼
/ask ──► [语义缓存?] ──命中──► 直接返回(省 token)
  │ miss
  ▼
[retriever: 向量 + BM25 --RRF--> Rerank] --top_k-->
  │
  ▼
[generator: 拼 context + System Prompt(防幻觉) -> LLM]
  │
  ▼
{answer, sources[], usage}  ──► 写入语义缓存

/agent/session (SSE):
  retrieve(LLM Function Calling: search_knowledge)
    -> ask(出题 + get_scoring_rubric)
    -> simulate_answer(LLM 模拟求职者)
    -> evaluate(LLM-judge,温度 0)
    -> decide(条件边)
        ├─ score<70 且 rounds 未到 -> followup -> ask (循环)
        └─ 否则 -> advise(学习建议 + save_note) -> done
```

## 在线链接

- HF Spaces(待部署):_

## 与面试智练的关系

「面试智练」App 的 AI 评估在**客户端**直连模型;本服务把同一套评估(同一份 rubric 数据)用**完整 RAG + Agent**在**服务端**重实现。评估的结构化标准(mustHave/goodToHave/commonMistakes/scoreWeights)与面试智练**刻意一致**,形成"同一产品思想的服务端演进"叙事。

## 测试覆盖

60 个 pytest 测试,不依赖真实 LLM(用 FakeLLM mock):

- `test_evaluator.py`(8):评估器解析/降级/可复现
- `test_question_gen.py`(4):出题
- `test_splitter.py`(6):切分逻辑
- `test_store.py`(5):内存向量库
- `test_retriever.py`(4):向量/混合/RRF/BM25
- `test_rag_api.py`(8):/ingest /ask 端到端 + 缓存命中 + 注入拦截 + metrics
- `test_agent.py`(7):Function Calling + SSE 流 + 追问
- `test_guardrails.py`(12):注入/PII/JSON 校验
- `test_cache.py`(3):语义缓存
- `test_ratelimit.py`(3):限流 + 自带 Key 绕过
