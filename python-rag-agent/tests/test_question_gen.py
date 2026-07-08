"""出题模块单测。"""

from __future__ import annotations

import pytest

from app.interview.question_gen import generate_questions
from app.rag.loader import get_kb


def test_generate_questions_returns_recall_prompts():
    """出题应直接返回 topic 的 recallPrompts(已人工撰写)。"""
    qs = generate_questions("java.concurrency.volatile", count=2)
    assert len(qs) >= 1
    assert qs[0].question_id.startswith("java.concurrency.volatile.recall.")
    assert qs[0].prompt  # 非空
    assert isinstance(qs[0].difficulty, int)


def test_generate_questions_difficulty_filter():
    """指定 difficulty 时应只返回该难度的题。"""
    all_qs = generate_questions("database.mysql-index", count=10)
    if not all_qs:
        pytest.skip("样例 topic 无 recallPrompts")
    target_diff = all_qs[0].difficulty
    filtered = generate_questions("database.mysql-index", difficulty=target_diff, count=10)
    assert all(q.difficulty == target_diff for q in filtered)


def test_generate_questions_unknown_topic_raises():
    """未知 topic 应抛 ValueError(便于上层返回 404)。"""
    with pytest.raises(ValueError, match="topic 不存在"):
        generate_questions("not.exist")


def test_kb_loaded_sample_topics():
    """样例知识库应加载到至少 3 个 production topic。"""
    kb = get_kb()
    assert kb.count() >= 3
    assert kb.get("java.concurrency.volatile") is not None
    assert kb.get("database.mysql-index") is not None
    assert kb.get("python.gil") is not None
