# RAG 评测报告

> 评测集:`eval_set.hard.json` | 评测条数:15
> Embedding:`local` / `BAAI/bge-small-zh-v1.5` | 生成时间:见 metrics.json

## 检索质量对比(纯向量 / 混合 RRF / 混合+rerank)

| 模式 | hit_rate | MRR | context_coverage | avg_latency_ms |
|------|----------|-----|-------------------|----------------|
| vector | 0.8667 | 0.8333 | 0.7907 | 232.73 |
| hybrid | 1.0 | 0.8833 | 0.7661 | 147.15 |
| hybrid_rerank | 1.0 | 0.8833 | 0.7661 | 145.62 |

> **结论**:混合检索相比纯向量,hit_rate +0.1333。

## 指标说明
- **hit_rate**:top_k 检索结果里是否包含 relevant_ids(检索命中率)
- **MRR**:第一个相关结果的倒数排名(Mean Reciprocal Rank,越高越好)
- **context_coverage**:ground_truth 关键词在检索 context 的命中比例(召回质量粗略)
- **avg_latency_ms**:平均检索延迟

## 生成质量指标(RAGAS)
> 未计算(需 `--with-ragas` 且装 ragas + 配 OPENAI_API_KEY)。
> 检索质量指标无需 LLM,已客观计算。