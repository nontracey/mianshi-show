# mianshi-zhilian-mcp

> 面试智练的 MCP 工具服务层：把教练能力（题目检索 / 评测执行 / Coach 会话）以 MCP 工具形式暴露，**每个工具带超时、重试（幂等键）、权限作用域、结构化审计**；Skills 层做版本化与漂移检测；评测层带对照组协议与发布门。

**位置与定位**：本目录在 `mianshi-show` 仓库的 `mcp` 分支上（`mcp/` 子目录，避免与三语言对照实现冲突）。上游 `~/code/mianshi-zhilian-app`（Flutter 客户端与 `lib/coach/` Agent 运行时，四端已发布 v0.1.8）；本目录是其服务侧子系统，不是第二个产品。show 仓库原本的三语言对照实现（python-rag-agent / java-ai-service / dotnet-ai-service）仍留在 main 分支，本分支不依赖它们。

## 为什么是这个形态（30 秒版）

我完整拆解过前公司（医疗 SaaS 行业）AI 平台的 MCP 接入，工具注册有三处裸奔——**无超时、无重试、无权限校验**，且工具注册与对外暴露的控制器完全脱钩。本服务就是对着这份拆解清单逐条补齐的自研实现：

| 企业平台拆解发现的裸奔点 | 本服务的对应机制 |
|---|---|
| 工具调用无超时 | 每工具显式 `timeout`，超时算失败走重试/降级 |
| 无重试与幂等 | 幂等键 + 有界重试（可重试错误才重试） |
| 无权限校验 | 工具级权限作用域（概念沿用客户端 ConfirmationToken） |
| 控制器与注册工具脱钩 | 工具注册即契约：manifest 版本化，代码与声明不一致视为缺陷 |
| （拆解发现的系统性问题）失败静默 | 结构化审计日志可回放；副作用可恢复（执行前快照） |

## 四层结构

```
tool/    工具层：question-search / eval-run / coach-session
         每个工具 = 契约(输入/输出/失败行为) + 超时 + 幂等键 + 权限作用域 + 审计事件
skill/   Skill 层：工具 manifest 版本化；内容变更→重扫/重签后才可被调用（漂移检测）
eval/    评测层：对照组协议（带工具 vs 不带跑同题集）→ 功能/增益/安全三维分
         + 证据链/前提条件/置信度分级；Token 成本统计与工具返回去噪
（数据层）PostgreSQL + pgvector（Docker Compose 提供），题目知识库检索
```

## 文档

- [docs/ADR.md](docs/ADR.md) — 关键取舍记录（为什么自研不用 LangChain、为什么幂等键、为什么漂移必须重扫、为什么验收不用端到端准确率）
- `docs/eval-report.md` — 对照组评测报告 + 外部客户端验收记录

## 状态

- [x] 仓库脚手架
- [x] 工具层三工具实现 + 失败路径测试（治理三件套测试全绿）
- [x] Skill manifest 版本化 + 漂移重扫（sha256，变更未重扫拒绝调用）
- [ ] pgvector 数据层（Docker Compose；当前默认内存关键词检索，向量通道留 profile 位——已知短板）
- [x] 对照组评测协议 + 报告（`docs/eval-report.md`，含 bad case 与短板声明）
- [x] 成本统计（CostLedger）+ 去噪（coach 会话输出去控制符限长）
- [x] 审计 JSONL 追加式落盘（重启可回放）+ Skill manifest 持久化（版本跨进程只增不减）+ 持久化测试
- [x] 已知短板清单（ADR-7：无流式/无端点轮换/orTimeout 线程不中断/评测集样本小/基线组偏弱）
- [x] 外部客户端验收：官方 MCP SDK 客户端（Streamable HTTP）initialize → tools/list → tools/call 全链路通过；顺带修复缺参静默错误

## 红线（贡献前必读）

- **前公司框架与代码不得直接使用（2026-09-22 红线）**：前公司平台只作机制参照（拆解结论本地存档，不进本公开仓库），实现代码一律原创或基于开源组件（spring-ai 等）；不搬运、不改名复用公司代码。
- 不做高并发/压测叙事；不写用户量；不承诺生产级 SLA——这是个人子系统的工程验证，卖点判据不卖规模。
- 审计与元数据如实记录，失败不静默。
- **交付时必须同时写"做得好的"与"做得不好的"**：每个 ADR 附已知短板；评测报告必须含 bad case 与未达标项；对应内容同步进防守稿（短板是追问的第一个入口，先自己说，不等对方戳）。

## 手机演示（对外展示页）

服务自带一个 demo 页（`src/main/resources/static/index.html`），与 `/mcp` 同源直调，无需任何前端构建：

```bash
cd mcp && mvn -DskipTests package
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java -jar target/mianshi-zhilian-mcp-0.1.0-SNAPSHOT.jar --server.port=18080
```

- 本机：http://127.0.0.1:18080/
- 手机（ZeroTier 网内）：http://10.147.19.248:18080/
- 页面能力：三工具真实调用（检索 / 教练会话 / 对照组评测）、治理清单、已知边界（ADR-7 短板先说）。每次调用落 `data/audit.jsonl`。
- ⚠️ 本机 curl 验证带 `--noproxy '*'`，否则 Clash 代理会截胡返回 502。
