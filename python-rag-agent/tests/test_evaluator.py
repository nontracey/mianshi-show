"""LLM-as-judge 评估单测:用 FakeLLM 避免真实 API 调用,保证可重复运行。

验收要点(M1):对同一回答多次评估结果一致(temperature=0)+ 命中/遗漏正确解析。
"""

from __future__ import annotations

import pytest

from app.interview import evaluator
from app.interview.evaluator import _extract_topic_id, _parse_eval, evaluate_answer
from app.infra.llm import LLMError
from app.schemas import Evaluation


def test_extract_topic_id():
    assert _extract_topic_id("java.concurrency.volatile.recall.1") == "java.concurrency.volatile"
    assert _extract_topic_id("database.mysql-index.recall.2") == "database.mysql-index"
    # fallback:无 .recall. 段时剥最后一段
    assert _extract_topic_id("java.concurrency.volatile") == "java.concurrency"


def test_parse_eval_full_json():
    content = {
        "score": 85,
        "dimension_scores": {"coverage": 80, "accuracy": 90, "interviewExpression": 85, "depth": 85},
        "hit_points": ["能说清可见性"],
        "missed": ["未提 happens-before"],
        "mistakes": ["误以为保证原子性"],
        "feedback": "回答清晰,但缺少 happens-before 的说明。",
    }
    ev = _parse_eval(content)
    assert ev.score == 85
    assert ev.dimension_scores["accuracy"] == 90
    assert "能说清可见性" in ev.hit[0]
    assert "未提 happens-before" in ev.missed[0]
    assert "误以为保证原子性" in ev.mistakes[0]
    assert "happens-before" in ev.feedback
    assert ev.degraded is False


def test_parse_eval_field_aliases():
    """LLM 可能用不同字段名,应容错。"""
    content = {
        "score": "70",
        "hit": ["a"],
        "missed_points": ["b"],
        "mistakes": ["c"],
        "feedback": "ok",
    }
    ev = _parse_eval(content)
    assert ev.score == 70
    assert ev.hit == ["a"]
    assert ev.missed == ["b"]
    assert ev.mistakes == ["c"]


def test_parse_eval_score_clamp():
    """分数越界应被 clamp 到 [0, 100]。"""
    ev = _parse_eval({"score": 150, "feedback": ""})
    assert ev.score == 100
    ev = _parse_eval({"score": -10, "feedback": ""})
    assert ev.score == 0


@pytest.mark.asyncio
async def test_evaluate_answer_normal_path(fake_llm):
    """正常路径:LLM 返回 JSON -> 解析为 Evaluation。"""
    fake_llm._json = {
        "score": 75,
        "dimension_scores": {"coverage": 70, "accuracy": 80, "interviewExpression": 75, "depth": 75},
        "hit_points": ["可见性"],
        "missed": ["原子性未解释清楚"],
        "mistakes": [],
        "feedback": "补充 i++ 的读-改-写过程。",
    }
    ev = await evaluate_answer(
        "java.concurrency.volatile.recall.1",
        "volatile 保证可见性,但不保证原子性。",
        llm=fake_llm,
    )
    assert isinstance(ev, Evaluation)
    assert ev.score == 75
    assert "可见性" in ev.hit[0]
    assert "原子性" in ev.missed[0]
    assert ev.degraded is False
    # temperature=0 透传(可复现性验收点):FakeLLM 记录调用次数,确认只调一次
    assert fake_llm._call_count == 1


@pytest.mark.asyncio
async def test_evaluate_answer_reproducible(fake_llm):
    """同一回答多次评估,结果应一致(temperature=0 + 同输入 -> 同输出)。"""
    fake_llm._json = {
        "score": 80,
        "hit_points": ["a"],
        "missed": ["b"],
        "mistakes": [],
        "feedback": "ok",
        "dimension_scores": {},
    }
    r1 = await evaluate_answer("java.concurrency.volatile.recall.1", "answer", llm=fake_llm)
    fake_llm._call_count = 0
    r2 = await evaluate_answer("java.concurrency.volatile.recall.1", "answer", llm=fake_llm)
    assert r1.score == r2.score
    assert r1.hit == r2.hit
    assert r1.missed == r2.missed


@pytest.mark.asyncio
async def test_evaluate_answer_degrades_on_json_failure(fake_llm):
    """LLM 两次都返回非 JSON -> 降级为文本反馈(degraded=True)。"""
    fake_llm._always_raise = LLMError("invalid json")
    ev = await evaluate_answer(
        "java.concurrency.volatile.recall.1",
        "answer",
        llm=fake_llm,
    )
    assert ev.degraded is True
    assert ev.score == 0
    assert "不可用" in ev.feedback or "失败" in ev.feedback
    # 重试一次 + 降级 = 共 2 次调用
    assert fake_llm._call_count == 2


@pytest.mark.asyncio
async def test_evaluate_answer_unknown_question_id(fake_llm):
    """question_id 对应的 topic 不存在应抛 ValueError。"""
    with pytest.raises(ValueError, match="topic 不存在"):
        await evaluate_answer("not.exist.recall.1", "answer", llm=fake_llm)
