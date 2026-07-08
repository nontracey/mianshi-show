"""从真实知识库(mianshi-zhilian-content 本地 clone)自动构建检索评测集。

思路:每个 topic 自带 recallPrompts(现成面试题)和唯一 id。
  - question      = 该 topic 的一条 recallPrompt
  - relevant_ids  = [topic.id]           <- 真实 KB id,与语料一致
  - ground_truth  = summary + mustHave   <- 供 context_coverage 计算关键词命中

这样 hit_rate/MRR 衡量的是「在全量 KB 里能否检索回该问题的源 topic」,有真实区分度。

用法:
  KB_CONTENT_PATH=/path/to/mianshi-zhilian-content \
    uv run python benchmarks/build_eval_set.py --n 30 --out ../data/eval_set.full.json
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from app.config import get_settings
from app.rag.loader import _load_from_local_clone


def build(n: int, seed: int, out: Path) -> None:
    s = get_settings()
    if not s.kb_content_path:
        raise SystemExit("请设置 KB_CONTENT_PATH 指向 mianshi-zhilian-content 本地 clone")
    root = Path(s.kb_content_path)
    topics, version = _load_from_local_clone(root)

    # 只保留 production 且有 recallPrompts 的 topic
    pool = [
        t for t in topics
        if t.get("status") == "production" and t.get("recallPrompts") and t.get("id")
    ]
    print(f"KB version={version} | production+可出题 topic={len(pool)} / 总 {len(topics)}")

    rng = random.Random(seed)
    picked = rng.sample(pool, min(n, len(pool)))

    evals = []
    for t in picked:
        prompt = t["recallPrompts"][0]["prompt"]
        rubric = t.get("rubric") or {}
        must = rubric.get("mustHave", [])
        ground_truth = (t.get("summary", "") + " " + " ".join(must)).strip()
        evals.append(
            {
                "question": prompt,
                "relevant_ids": [t["id"]],
                "ground_truth": ground_truth,
                "domain": t.get("domain", ""),
            }
        )

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps({"contentVersion": version, "n": len(evals), "evals": evals}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"已写入 {out} | 评测条数={len(evals)} | KB version={version}")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--n", type=int, default=30, help="抽样评测条数")
    p.add_argument("--seed", type=int, default=42, help="随机种子(可复现)")
    p.add_argument("--out", default=str(_ROOT.parent / "data" / "eval_set.full.json"))
    args = p.parse_args()
    build(args.n, args.seed, Path(args.out))


if __name__ == "__main__":
    main()
