# benchmarks · RAG 评测

## 产出

- `metrics.json`:三档检索模式(vector / hybrid / hybrid_rerank)的客观指标
- `report.md`:人类可读的对比报告

## 指标

不依赖 RAGAS 库(避免网络阻塞),自实现客观指标:

| 指标 | 说明 |
|------|------|
| `hit_rate` | top_k 检索结果里是否包含 relevant_ids |
| `mrr` | 第一个相关结果的倒数排名(Mean Reciprocal Rank) |
| `context_coverage` | ground_truth 关键词在检索 context 的命中比例 |
| `avg_latency_ms` | 平均检索延迟 |

> 生成质量指标(faithfulness / answer_relevancy)需要 RAGAS + LLM,用 `--with-ragas` 启用(需 `uv sync --extra eval`)。

## 跑法

```bash
# 1. 配好 .env(OPENAI_API_KEY 等)
# 2. 跑三档对比(真实产出 metrics.json + report.md)
uv run python benchmarks/run_ragas.py --compare

# 限制条数快速验证
uv run python benchmarks/run_ragas.py --compare --limit 4

# 无 key 时验证脚本流程(数值无意义,不写 metrics.json)
uv run python benchmarks/run_ragas.py --dry-run --compare
```

## 当前状态

- 脚本逻辑已用 `--dry-run` 验证通过(3 个样例 topic,17 chunks,三档模式都跑通)
- 真实数字待填入 `OPENAI_API_KEY` 后跑 `--compare` 产出
- 期待看到:**hybrid 的 MRR/hit_rate 高于 vector**(混合检索优势量化)
