"""造"难"评测集(item 2):用 LLM 为每个 topic 生成**短关键词式查询**,
不抄 recallPrompt 原文,专门制造"用户口语化/关键词化、与语料措辞不同"的检索压力,
看混合检索(BM25 关键词)能否比纯向量更稳。

用法(需 glm chat 可用):
  LOCAL_EMBEDDING_MODEL=<abs> EMBEDDING_PROVIDER=local HF_HUB_OFFLINE=1 \
    uv run --no-sync python benchmarks/build_hard_eval.py --n 15
"""
from __future__ import annotations

import argparse, asyncio, json, random, sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from app.config import get_settings
from app.infra.llm import LLMClient
from app.rag.loader import _load_from_local_clone

# 造题 System Prompt:约束 LLM 生成"像真实用户会敲的短查询"——
# 口语化/关键词化 + 不照抄标题原词,刻意制造查询与语料的措辞差异。
# 这个脚本调用时配 temperature=0.7,允许措辞多样化(造数据要多样性,不要确定性)。
SYS = (
    "你是模拟真实用户搜索行为的助手。给你一个技术知识点的标题和摘要,"
    "请生成**一个**用户可能在搜索框敲的**短查询**(8-20字),要求:"
    "①口语化或关键词化,②尽量不照抄标题原词,③聚焦该知识点的核心考点。"
    "只输出查询本身,不要解释。"
)


async def build(n: int, seed: int, out: Path) -> None:
    """构建"难"评测集:抽样 topic → LLM 逐条生成口语化查询 → 写 JSON。

    与 build_eval_set.py 的区别:question 不再复用 recallPrompt
    (那是精心撰写的问题,检索太容易),而是 LLM 生成的短关键词式
    查询,专门压测"措辞不匹配"场景下 BM25 关键词路的价值。
    单条生成失败(如 LLM 异常)跳过不中断,保证脚本健壮。
    """
    s = get_settings()
    if not s.kb_content_path:
        raise SystemExit("需 KB_CONTENT_PATH 指向本地 clone")
    topics, version = _load_from_local_clone(Path(s.kb_content_path))
    pool = [t for t in topics if t.get("status") == "production" and t.get("id") and t.get("summary")]
    picked = random.Random(seed).sample(pool, min(n, len(pool)))

    llm = LLMClient(s)
    evals = []
    for t in picked:
        prompt = f"标题:{t['title']}\n摘要:{t.get('summary','')[:200]}"
        try:
            q, _ = await llm.chat(
                [{"role": "system", "content": SYS}, {"role": "user", "content": prompt}],
                temperature=0.7,
            )
            q = q.strip().strip('"').split("\n")[0][:40]  # 清洗:去引号、只取首行、限长,防 LLM 多输出
        except Exception as e:
            print("跳过", t["id"], e); continue
        rubric = t.get("rubric") or {}
        gt = (t.get("summary", "") + " " + " ".join(rubric.get("mustHave", []))).strip()
        evals.append({"question": q, "relevant_ids": [t["id"]], "ground_truth": gt, "domain": t.get("domain", "")})
        print(f"{t['id'][:40]:42s} -> {q}")

    out.write_text(json.dumps({"contentVersion": version, "n": len(evals), "evals": evals}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n已写入 {out} | {len(evals)} 条")


def main() -> None:
    """CLI 入口:默认抽 15 条、seed=7,输出到 data/eval_set.hard.json。"""
    p = argparse.ArgumentParser()
    p.add_argument("--n", type=int, default=15)
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--out", default=str(_ROOT.parent / "data" / "eval_set.hard.json"))
    args = p.parse_args()
    asyncio.run(build(args.n, args.seed, Path(args.out)))


if __name__ == "__main__":
    main()
