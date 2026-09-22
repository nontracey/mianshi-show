# 对照组评测报告 · eval-run test-run-1

> 由 `EvalHarness` 生成（`mvn test` 内含同名运行）。应答器为**确定性检索应答器**（可复现，可当门禁）；
> 换成真实模型应答器后本协议不变。评测集样本小（3 条）是已知短板（ADR-7）。

## 结果（三维分，满分 = 题目数 3）

| 维度 | 得分 | 含义 |
|---|---|---|
| 功能分 | 3/3 | 两组输出均通过 schema 校验（answer/evidence 字段齐全非空） |
| 增益分 | 3/3 | 带工具组命中期望关键词；基线组全部"依据不足"——增益 = 3 - 0 |
| 安全分 | 3/3 | 审计轨迹无 unsafe 事件；工作区按 runId 隔离 |

**置信度：high**（3/3 条带证据来源）。

## 对照样本（G1）

- base（无工具）：`依据不足，需要检索支持。`
- augmented（带工具）：命中 `golden-set` 文档片段（含"黄金集/发布门"），证据来源 `inmemory:golden-set`。

## bad case / 已知短板（ADR-7，主动声明）

1. **评测集样本小**：3 条黄金项，全部来自自建对照集，不能推导全领域准确率。
2. **确定性应答器**：当前应答器是检索片段直出，不是真实 LLM 生成；换成模型应答器后增益数字会重测。
3. **安全分口径有限**：只覆盖"审计无 unsafe 事件 + 工作区隔离"，未做注入对抗样本。
4. **基线组太弱**：无工具时只能答"依据不足"，增益天然容易拿满——更公平的基线是"模型凭参数记忆作答"，留待接入真实模型后补测。
5. **Token 成本**：本组应答器为确定性实现，Token 消耗为 0；接真实模型后由 CostLedger 记录（coach-session 已接）。

## 复现

```bash
cd mcp && mvn test -Dtest=GovernanceAndEvalTest
# 工作区产物: target/eval-workspace-test/test-run-1/G*.md
```

## 外部客户端验收（2026-09-22，真实 MCP SDK 客户端）

- **客户端**：`@modelcontextprotocol/sdk`（官方 JS SDK）Streamable HTTP 客户端，initialize → tools/list → tools/call 全链路。
- **结果**：三个工具全部通过——
  1. `searchQuestions`：真实检索命中（rrf-hybrid 片段，含 15/15 vs 13/15 对照数据）；
  2. `coachSession`：模型端点未配置时返回结构化错误 `coach_not_configured`（设计行为，不静默）；
  3. `runEval`：带 runId 返回完整评测报告（3/3/3，置信度 high）。
- **验收发现并修复的问题**：spring-ai mcp-server-webmvc 1.1.0-M3 把参数校验错误映射为**空 text 内容块**（`isError:true` 但无任何文本），严格客户端（官方 SDK）直接拒收解析——失败信息整个丢失，违反「失败不静默」。**修复**：三工具参数改为非必填 + 工具内显式校验，所有错误以结构化 JSON 返回；新增 `MissingArgumentStructuredErrorTest` 锁定行为（mvn test 11 项全绿）。
- **验收环境备注**：服务以 `--server.port=18080` 本机运行；运行时用 JDK 26 跑 Java 21 字节码（向下兼容，无告警）。
