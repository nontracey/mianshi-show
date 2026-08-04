"""出题:直接返回 topic 的 recallPrompts(已人工撰写,无需生成)。

设计动机:知识库中每个 topic 已有人工撰写的回忆题(recallPrompts),直接复用比
让 LLM 现场生成更稳——题目质量有保证、无幻觉、零 token 成本、可复现。
question_id 优先取 recallPrompt 自带 id,缺失时用 "<topic_id>.recall.N" 兜底。

可选让 LLM 基于 rubric 产出变体题(M5 扩展);M1 先做最直接的。
"""

from __future__ import annotations

import logging

from app.rag.loader import KnowledgeBase, Topic, get_kb
from app.schemas import Question

logger = logging.getLogger(__name__)


def generate_questions(
    topic_id: str,
    *,
    difficulty: int | None = None,
    count: int = 1,
    kb: KnowledgeBase | None = None,
) -> list[Question]:
    """从 topic 的 recallPrompts 出题。

    - difficulty 过滤:None 表示不过滤;指定则只返回该难度。
    - count:返回条数(过滤后的前 N 条)。

    返回:Question 列表。边界:topic 不存在抛 ValueError(上层据此返回 404);
    过滤后无题则返回空列表。
    """
    base = kb or get_kb()
    topic: Topic | None = base.get(topic_id)
    if topic is None:
        raise ValueError(f"topic 不存在:{topic_id}")

    prompts = topic.recall_prompts or []
    if difficulty is not None:
        prompts = [p for p in prompts if p.get("difficulty") == difficulty]

    result: list[Question] = []
    for p in prompts[:count]:
        result.append(
            Question(
                # question_id 优先用数据自带 id,缺失时按序号兜底生成。
                question_id=p.get("id", f"{topic_id}.recall.{len(result)+1}"),
                prompt=p.get("prompt", ""),
                # 难度优先用题自带值,缺失时回退 topic 的整体难度。
                difficulty=p.get("difficulty", topic.difficulty),
            )
        )
    return result
