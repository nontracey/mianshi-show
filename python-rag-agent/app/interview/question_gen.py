"""出题:直接返回 topic 的 recallPrompts(已人工撰写,无需生成)。

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
                question_id=p.get("id", f"{topic_id}.recall.{len(result)+1}"),
                prompt=p.get("prompt", ""),
                difficulty=p.get("difficulty", topic.difficulty),
            )
        )
    return result
