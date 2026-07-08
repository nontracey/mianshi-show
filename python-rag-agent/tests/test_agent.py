"""Agent 编排单测:验证 Function Calling 工具被调 + 完整 SSE 流 + 追问逻辑。"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.agent.graph import AgentOrchestrator, reset_orchestrator
from app.agent.tools import execute_tool, get_scoring_rubric, save_note, search_knowledge
from app.main import app


@pytest.mark.asyncio
async def test_search_knowledge_tool_returns_docs(ingested):
    """search_knowledge 工具应返回检索结果。"""
    res = await search_knowledge("volatile 可见性", top_k=2)
    assert "docs" in res
    assert len(res["docs"]) >= 1
    assert res["docs"][0]["topic_id"]


def test_get_scoring_rubric_tool():
    """get_scoring_rubric 工具应返回 topic 的 rubric。"""
    res = get_scoring_rubric("java.concurrency.volatile.recall.1")
    assert res["topic_id"] == "java.concurrency.volatile"
    assert "mustHave" in res["rubric"]


def test_save_note_tool():
    res = save_note("学习笔记:volatile 不保证原子性")
    assert res["saved"] is True
    assert res["length"] > 0


@pytest.mark.asyncio
async def test_execute_tool_unknown_returns_error():
    res = await execute_tool("not_a_tool", {})
    assert "error" in res


@pytest.mark.asyncio
async def test_agent_retrieve_uses_function_calling(ingested):
    """retrieve 节点应通过 Function Calling 调 search_knowledge 工具。"""
    ingested._text = "volatile 保证可见性,但不保证原子性,因为 i++ 是复合操作。"
    ingested._json = {
        "score": 75,
        "dimension_scores": {"coverage": 70, "accuracy": 80, "interviewExpression": 75, "depth": 75},
        "hit_points": ["可见性"],
        "missed": ["happens-before"],
        "mistakes": [],
        "feedback": "补充 happens-before 关系。",
    }

    reset_orchestrator()
    orch = AgentOrchestrator(llm=ingested)
    events = []
    async for ev in orch.run("java.concurrency.volatile", rounds=1):
        events.append(ev)

    # 第一个事件应是 retrieve,且 tool_call.name == search_knowledge
    assert events[0].type == "retrieve"
    tc = events[0].payload.get("tool_call") if events[0].payload else None
    assert tc is not None, "retrieve 节点应有 tool_call(Function Calling)"
    assert tc["name"] == "search_knowledge"

    # 事件序列应包含 question/answer/evaluate/advise/done
    types = [e.type for e in events]
    assert "question" in types
    assert "answer" in types
    assert "evaluate" in types
    assert "advise" in types
    assert types[-1] == "done"


@pytest.mark.asyncio
async def test_agent_followup_on_low_score(ingested):
    """评估低分(score<70)且 rounds>1 时应触发 followup 追问。"""
    ingested._text = "我不太清楚。"
    ingested._json = {
        "score": 40,
        "dimension_scores": {},
        "hit_points": [],
        "missed": ["可见性", "原子性"],
        "mistakes": ["误以为保证原子性"],
        "feedback": "回答不充分。",
    }

    reset_orchestrator()
    orch = AgentOrchestrator(llm=ingested)
    events = []
    async for ev in orch.run("java.concurrency.volatile", rounds=2):
        events.append(ev)

    types = [e.type for e in events]
    assert "followup" in types, "低分时应触发 followup 事件"
    questions = [e for e in events if e.type == "question"]
    assert len(questions) >= 2


def test_agent_api_empty_store_returns_400(mock_stack):
    """向量库为空时 /api/agent/session 返回 400。"""
    with TestClient(app) as c:
        r = c.post("/api/agent/session", json={"topic": "java.concurrency.volatile", "rounds": 1})
        assert r.status_code == 200
        body = r.json()
        assert body["code"] != 0
        assert "ingest" in body["message"].lower() or "入库" in body["message"]


def test_agent_api_sse_stream(ingested):
    """/api/agent/session 应返回 SSE 流,含 retrieve/question/done 事件。"""
    ingested._text = "volatile 保证可见性。"
    ingested._json = {
        "score": 80,
        "dimension_scores": {},
        "hit_points": ["可见性"],
        "missed": [],
        "mistakes": [],
        "feedback": "回答正确。",
    }
    reset_orchestrator()

    with TestClient(app) as c:
        with c.stream("POST", "/api/agent/session", json={"topic": "java.concurrency.volatile", "rounds": 1}) as r:
            assert r.status_code == 200
            event_types = []
            for line in r.iter_lines():
                if line.startswith("event:"):
                    event_types.append(line.split(":", 1)[1].strip())
            assert "retrieve" in event_types
            assert "question" in event_types
            assert "done" in event_types
