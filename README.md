# AI 应用开发作品集 · mianshi-show

> 10 年后端工程 + AI Agent 应用开发。这里是我**独立完成**的服务端 AI 作品集：
> 同一套「AI 面试陪练」能力（RAG 知识问答 + 出题 + LLM 评估 + Agent 编排），用 **Python / Java / .NET** 各实现一遍，**对外接口完全一致**，可按团队技术栈落地。

🔎 **想快速看清架构、真实评测数字、如何运行** → 读 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**

---

## 🧭 一眼看全

| 项目 | 语言 / 栈 | 定位 | 状态 |
|------|----------|------|------|
| **[面试智练](https://mianshizhilian-app.nontracey.de5.net)** | Flutter 多端 + Cloudflare Workers | 旗舰 · 已上线真产品 | ✅ 上线（[在线体验](https://mianshizhilian-app.nontracey.de5.net) · [仓库](https://github.com/nontracey/mianshi-zhilian-app)） |
| **python-rag-agent** | Python · FastAPI · LangGraph · sentence-transformers | 主项目 · RAG→Agent 全链路 | ✅ 全链路 + 评测 + 60 单测通过 |
| **java-ai-service** | Java 17 · Spring Boot 3 · Spring AI | 差异化 · Java 版同能力 | ✅ 构建通过 · RAG 端到端跑通 |
| **dotnet-ai-service** | .NET 8 · Minimal API | 三语言收口 | ✅ 构建通过 · RAG 端到端跑通 |

> 「面试智练」是已上线的独立产品（Flutter 栈，另一个仓库）；本仓库放三份**服务端 AI 实现**。

---

## 💡 一句话叙事

> "我上线过一个多端面试学习产品『面试智练』，里面的 AI 评估是客户端直连模型做的。为了把它做成**企业级后端能力**，我用完整的 **RAG + Agent** 架构在服务端重新实现了一套『AI 面试陪练服务』——而且 **Python / Java / .NET 三个版本都写了、都真跑通了**，证明这套 AI 能力能落到任何团队的技术栈上。"

---

## ⭐ 一个真实数据点（不是估计，是跑出来的）

用**两组评测集**测混合检索 vs 纯向量（429 知识点语料，本地 bge 编码）：

- 语义清晰的常规问答：纯向量 hit_rate 已 100%，混合持平；
- **用户口语化 / 关键词化的难查询：纯向量 87% → 混合检索拉回 100%**。

→ 混合检索的价值**取决于查询类型**，这是测出来的边界。详见 [docs/ARCHITECTURE.md §4](docs/ARCHITECTURE.md)。

---

## 🚀 快速开始

```bash
# 项目 B（Python）—— 主项目
cd python-rag-agent && cp .env.example .env    # 填 OpenAI 兼容 Key（可切本地免费 embedding）
uv sync && uv run uvicorn app.main:app --reload  # http://127.0.0.1:8000/docs
```

C（`./gradlew bootRun`）、D（`dotnet run`）见各自目录 README。完整说明与三语言运行方式见 [docs/ARCHITECTURE.md §6](docs/ARCHITECTURE.md)。

---

## 🏗️ 仓库结构

```
mianshi-show/
├── README.md                 ← 本文件：作品集门户
├── docs/ARCHITECTURE.md      ← 架构 / 契约 / 真实评测结果 / 运行方式
├── data/                     ← 共享知识库样例与评测集
├── python-rag-agent/         ← 项目 B（Python，主项目）
├── java-ai-service/          ← 项目 C（Java，Spring AI）
└── dotnet-ai-service/        ← 项目 D（.NET）
```

---

## 🔐 说明

- 所有 AI 调用走 **OpenAI 兼容接口**（`base_url` + `api_key` 可配），支持通义/智谱/DeepSeek/OpenAI/本地模型；embedding 可用本地 `bge-small-zh-v1.5`，**零成本离线运行**。
- **密钥永不入库**：用 `.env`（已 `.gitignore`），仓库内只有 `.env.example` 模板。
