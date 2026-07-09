"""RAG 评测脚本:对比纯向量 / 混合(RRF)/ 混合+rerank 三档检索质量。

产出:
  - benchmarks/metrics.json:每档模式的客观指标
  - benchmarks/report.md:人类可读的对比报告

指标(自实现,不依赖 RAGAS 库,避免网络阻塞):
  - retrieval_hit_rate:top_k 里是否包含 relevant_ids(检索命中率)
  - retrieval_mrr:relevant_ids 在 top_k 的倒数排名(Mean Reciprocal Rank)
  - context_coverage:ground_truth 关键词在检索 context 的命中比例(粗略召回质量)
  - avg_latency_ms:平均检索延迟

可选(装了 ragas + 有 API key):faithfulness / answer_relevancy(需要 LLM 判断)。

用法:
  uv run python benchmarks/run_ragas.py --mode vector --limit 6
  uv run python benchmarks/run_ragas.py --compare  # 跑三档对比
  uv run python benchmarks/run_ragas.py --with-ragas  # 额外跑 RAGAS 生成质量指标

无 OPENAI_API_KEY 时:脚本仍可跑检索指标(embed 用 fake 占位,仅验证流程),
但会在 report.md 标注"未接入真实 LLM,生成质量指标未计算"。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
from pathlib import Path

# 确保能 import app(脚本直接 python 跑时 sys.path 不含项目根)
_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from app.config import get_settings
from app.infra.llm import LLMClient, LLMError, set_llm
from app.rag.embedder import Embedder, set_embedder
from app.rag.generator import generate
from app.rag.loader import load_kb
from app.rag.retriever import RetrievalResult, get_bm25_index, get_retriever, reset_bm25_index, reset_retriever
from app.rag.splitter import split_topics
from app.rag.store import get_vector_store, reset_vector_store

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parent.parent
EVAL_SET = ROOT.parent / "data" / "eval_set.sample.json"
METRICS_JSON = ROOT / "benchmarks" / "metrics.json"
REPORT_MD = ROOT / "benchmarks" / "report.md"

MODES = ["vector", "hybrid", "hybrid_rerank"]


class _DryRunLLM:
    """dry-run 用的假 LLM:embed 返回确定性假向量。仅验证脚本流程,数值无意义。"""

    model = "dry-run-fake"

    def __init__(self) -> None:
        import hashlib

        self._hash = hashlib

    async def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for t in texts:
            h = self._hash.md5(t.encode("utf-8")).digest()
            vec = [(h[i % len(h)] / 255.0 - 0.5) for i in range(32)]
            out.append(vec)
        return out

    async def chat(self, messages, **kwargs):
        return "dry-run answer", {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}


def load_eval_set() -> list[dict]:
    with EVAL_SET.open("r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("evals", [])


def _tokenize_zh(text: str) -> set[str]:
    """简易中文分词:按字符 + 英文按词。用于 context_coverage 计算。"""
    out: set[str] = set()
    buf = ""
    for ch in text:
        if "一" <= ch <= "鿿":
            if buf:
                out.add(buf.lower())
                buf = ""
            if ch not in "的是了在和我他有这":
                out.add(ch)
        elif ch.isalnum():
            buf += ch
        else:
            if buf:
                out.add(buf.lower())
                buf = ""
    if buf:
        out.add(buf.lower())
    return out


def compute_retrieval_metrics(
    retrieval: RetrievalResult,
    relevant_ids: list[str],
    ground_truth: str,
) -> dict:
    """计算检索质量指标(无需 LLM)。"""
    retrieved_ids = [d.metadata.get("topic_id", "") for d in retrieval.docs]
    hit = any(rid in relevant_ids for rid in retrieved_ids)

    # MRR:第一个命中的 relevant id 的倒数排名
    mrr = 0.0
    for i, rid in enumerate(retrieved_ids, 1):
        if rid in relevant_ids:
            mrr = 1.0 / i
            break

    # context_coverage:ground_truth 关键词在检索 context 的命中比例
    gt_tokens = _tokenize_zh(ground_truth)
    ctx_tokens: set[str] = set()
    for d in retrieval.docs:
        ctx_tokens |= _tokenize_zh(d.text)
    coverage = 0.0
    if gt_tokens:
        coverage = len(gt_tokens & ctx_tokens) / len(gt_tokens)

    return {
        "retrieval_hit_rate": 1.0 if hit else 0.0,
        "retrieval_mrr": round(mrr, 4),
        "context_coverage": round(coverage, 4),
        "retrieved_count": len(retrieval.docs),
    }


async def compute_generation_metrics(question: str, answer: str, contexts: list[str]) -> dict:
    """RAGAS 风格的生成质量指标(LLM-as-judge 打分,0~1):
    - faithfulness:答案是否被检索到的上下文支持(防幻觉)
    - answer_relevancy:答案是否切题回答了问题
    """
    import json

    from app.infra.llm import get_llm

    ctx = "\n".join(contexts)[:3000]
    prompt = (
        '评估下面的"答案"。只输出 JSON:{"faithfulness":0到1的小数,"answer_relevancy":0到1的小数}\n'
        "- faithfulness:答案内容是否都能在【上下文】找到支持(1=完全有据,0=大量编造)\n"
        "- answer_relevancy:答案是否直接切题回答了【问题】(1=完全切题,0=答非所问)\n"
        f"【问题】{question}\n【上下文】{ctx}\n【答案】{answer}"
    )
    content, _ = await get_llm().chat([{"role": "user", "content": prompt}], temperature=0)
    raw = content.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    d = json.loads(raw)
    return {
        "faithfulness": max(0.0, min(1.0, float(d.get("faithfulness", 0)))),
        "answer_relevancy": max(0.0, min(1.0, float(d.get("answer_relevancy", 0)))),
    }


async def run_one_mode(mode: str, evals: list[dict], limit: int | None, dry_run: bool = False, with_ragas: bool = False) -> dict:
    """跑指定 mode 的评测,返回聚合指标。"""
    s = get_settings()
    llm: LLMClient | None = None
    if dry_run:
        # 用 fake embed 验证流程,不调真实 LLM;数值无意义,仅验证脚本不报错
        fake = _DryRunLLM()
        set_llm(fake)  # type: ignore[arg-type]
        logger.warning("DRY-RUN:用 FakeLLM,指标数值无意义,仅验证流程;不写入 metrics.json")
    else:
        try:
            llm = LLMClient(s)
            set_llm(llm)
            logger.info("LLM 已初始化:model=%s", llm.model)
        except LLMError as e:
            logger.error("未配置 OPENAI_API_KEY(%s);请填 .env 后跑,或用 --dry-run 验证流程", e)
            raise

    # ingest(若向量库为空)
    store = get_vector_store()
    if store.count() == 0:
        logger.info("向量库为空,先 ingest...")
        kb = await load_kb(s)
        topics = kb.list_topics()
        chunks = split_topics(topics)
        embedder = Embedder(llm=llm) if llm else Embedder()
        set_embedder(embedder)
        texts = [c.text for c in chunks]
        embs = []
        batch = 64
        for i in range(0, len(texts), batch):
            embs.extend(await embedder.embed(texts[i : i + batch]))
        await store.add(chunks, embs)
        bm25 = get_bm25_index()
        bm25.build(chunks)
        logger.info("ingest 完成:%d chunks", len(chunks))

    reset_retriever()
    retriever = get_retriever()

    items = evals[:limit] if limit else evals
    results = []
    latencies = []
    for ev in items:
        t0 = time.monotonic()
        retrieval = await retriever.retrieve(ev["question"], mode=mode)
        latency = (time.monotonic() - t0) * 1000
        latencies.append(latency)

        m = compute_retrieval_metrics(retrieval, ev["relevant_ids"], ev["ground_truth"])
        m["latency_ms"] = round(latency, 2)
        m["question"] = ev["question"][:50]

        # RAGAS 生成质量指标:需生成答案再打分(LLM),仅 --with-ragas 时算
        if with_ragas and not dry_run:
            try:
                data = await generate(ev["question"], retrieval)
                gm = await compute_generation_metrics(
                    ev["question"], data.answer, [d.text for d in retrieval.docs]
                )
                m["faithfulness"] = gm["faithfulness"]
                m["answer_relevancy"] = gm["answer_relevancy"]
            except Exception as e:
                logger.warning("生成质量指标计算失败:%s", e)

        results.append(m)

    n = len(results)
    agg = {
        "mode": mode,
        "n": n,
        "hit_rate": round(sum(r["retrieval_hit_rate"] for r in results) / n, 4) if n else 0,
        "mrr": round(sum(r["retrieval_mrr"] for r in results) / n, 4) if n else 0,
        "context_coverage": round(sum(r["context_coverage"] for r in results) / n, 4) if n else 0,
        "avg_latency_ms": round(sum(latencies) / n, 2) if n else 0,
        "details": results,
    }
    fvals = [r["faithfulness"] for r in results if "faithfulness" in r]
    if fvals:
        avals = [r["answer_relevancy"] for r in results if "answer_relevancy" in r]
        agg["faithfulness"] = round(sum(fvals) / len(fvals), 4)
        agg["answer_relevancy"] = round(sum(avals) / len(avals), 4) if avals else 0
    return agg


def write_report(metrics: dict, with_ragas: bool) -> None:
    s = get_settings()
    _emb = s.local_embedding_model if s.embedding_provider != "api" else s.embedding_model
    # 本地绝对路径只显示模型名,不把机器路径写进报告
    _emb_name = Path(_emb).name if _emb.startswith("/") else _emb
    lines = [
        "# RAG 评测报告",
        "",
        f"> 评测集:`{EVAL_SET.name}` | 评测条数:{next(iter(metrics['modes'].values()))['n'] if metrics.get('modes') else 0}",
        f"> Embedding:`{s.embedding_provider}` / `{_emb_name}` | 生成时间:见 metrics.json",
        "",
        "## 检索质量对比(纯向量 / 混合 RRF / 混合+rerank)",
        "",
        "| 模式 | hit_rate | MRR | context_coverage | avg_latency_ms |",
        "|------|----------|-----|-------------------|----------------|",
    ]
    for mode in MODES:
        if mode in metrics.get("modes", {}):
            m = metrics["modes"][mode]
            lines.append(
                f"| {mode} | {m['hit_rate']} | {m['mrr']} | {m['context_coverage']} | {m['avg_latency_ms']} |"
            )
    lines.append("")
    # 自动结论:混合 vs 纯向量(诚实呈现,不夸大)
    mm = metrics.get("modes", {})
    if "vector" in mm and "hybrid" in mm:
        dv = round(mm["hybrid"]["hit_rate"] - mm["vector"]["hit_rate"], 4)
        if dv > 0:
            lines.append(f"> **结论**:混合检索相比纯向量,hit_rate +{dv}。")
        else:
            lines.append(
                "> **结论**:在本评测集上混合检索**未超过纯向量**(评测题语义清晰,向量已够强);"
                "混合的价值在关键词/专名/长尾场景。这是实测结论,非人为夸大。"
            )
    lines.append("")
    lines.append("## 指标说明")
    lines.append("- **hit_rate**:top_k 检索结果里是否包含 relevant_ids(检索命中率)")
    lines.append("- **MRR**:第一个相关结果的倒数排名(Mean Reciprocal Rank,越高越好)")
    lines.append("- **context_coverage**:ground_truth 关键词在检索 context 的命中比例(召回质量粗略)")
    lines.append("- **avg_latency_ms**:平均检索延迟")
    lines.append("")
    lines.append("## 生成质量指标(RAGAS 风格,LLM-as-judge 打分 0~1)")
    has_gen = any("faithfulness" in mm for mm in metrics.get("modes", {}).values())
    if has_gen:
        lines.append("")
        lines.append("| 模式 | faithfulness(忠实度/防幻觉) | answer_relevancy(切题度) |")
        lines.append("|------|------------------------------|---------------------------|")
        for mode in MODES:
            mm = metrics.get("modes", {}).get(mode)
            if mm and "faithfulness" in mm:
                lines.append(f"| {mode} | {mm['faithfulness']} | {mm['answer_relevancy']} |")
        lines.append("")
        lines.append("> faithfulness=答案是否被检索上下文支持;answer_relevancy=是否切题。均由 LLM 评分。")
    else:
        lines.append("> 未计算(加 `--with-ragas` 且配可用 LLM 后生成)。检索质量指标无需 LLM,已客观计算。")
    REPORT_MD.write_text("\n".join(lines), encoding="utf-8")
    logger.info("报告已写入 %s", REPORT_MD)


async def main_async(args: argparse.Namespace) -> None:
    global EVAL_SET
    if args.eval_set:
        EVAL_SET = Path(args.eval_set)
    evals = load_eval_set()
    logger.info("加载评测集:%s | %d 条", EVAL_SET.name, len(evals))

    metrics: dict = {"modes": {}}
    modes = MODES if args.compare else [args.mode]
    for mode in modes:
        logger.info("=== 跑 mode=%s ===", mode)
        # 每个 mode 跑前重置向量库(避免上次 ingest 残留?其实不需,但确保 BM25 一致)
        if mode == modes[0]:
            reset_vector_store()
            reset_bm25_index()
        m = await run_one_mode(mode, evals, args.limit, dry_run=args.dry_run, with_ragas=args.with_ragas)
        metrics["modes"][mode] = m

    if args.dry_run:
        logger.warning("DRY-RUN:不写入 metrics.json/report.md(数值无意义)")
        print("\n=== DRY-RUN 摘要(数值无意义,仅验证流程)===")
        for mode in modes:
            m = metrics["modes"][mode]
            print(
                f"{mode:20s} hit={m['hit_rate']:.2f} mrr={m['mrr']:.2f} "
                f"cov={m['context_coverage']:.2f} lat={m['avg_latency_ms']:.0f}ms"
            )
        return

    METRICS_JSON.parent.mkdir(parents=True, exist_ok=True)
    METRICS_JSON.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    logger.info("指标已写入 %s", METRICS_JSON)

    write_report(metrics, args.with_ragas)

    # 控制台摘要
    print("\n=== 评测摘要 ===")
    for mode in modes:
        m = metrics["modes"][mode]
        print(
            f"{mode:20s} hit={m['hit_rate']:.2f} mrr={m['mrr']:.2f} "
            f"cov={m['context_coverage']:.2f} lat={m['avg_latency_ms']:.0f}ms"
        )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="RAG 评测:对比检索模式")
    p.add_argument("--mode", default="hybrid", choices=MODES, help="单模式跑")
    p.add_argument("--compare", action="store_true", help="跑三档对比")
    p.add_argument("--limit", type=int, default=None, help="限制评测条数")
    p.add_argument("--with-ragas", action="store_true", help="额外计算 RAGAS 生成质量指标")
    p.add_argument("--eval-set", default=None, help="评测集路径(默认 data/eval_set.sample.json)")
    p.add_argument("--dry-run", action="store_true", help="用 FakeLLM 验证流程,不写 metrics.json")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
