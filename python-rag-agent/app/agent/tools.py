"""Function Calling 工具:agent 可调用的能力。

每个工具 = Python 执行函数 + OpenAI tool schema。
agent 在 retrieve 节点把工具列表传给 LLM,LLM 决定调哪个(真实 Function Calling)。
"""

from __future__ import annotations

import logging
from typing import Any

from app.rag.loader import get_kb
from app.rag.retriever import get_retriever
from app.schemas import Source

logger = logging.getLogger(__name__)

# 工具执行函数(被 agent 节点或 LLM tool_call 调用)


async def search_knowledge(query: str, top_k: int = 4) -> dict[str, Any]:
    """检索知识库,返回相关 chunk。"""
    retriever = get_retriever()
    res = await retriever.retrieve(query, top_k=top_k, mode="hybrid")
    return {
        "query": query,
        "docs": [
            {
                "topic_id": d.metadata.get("topic_id", ""),
                "title": d.metadata.get("title", ""),
                "text": d.text[:200],
                "score": round(d.score, 4),
            }
            for d in res.docs
        ],
    }


def get_scoring_rubric(question_id: str) -> dict[str, Any]:
    """查 question_id 对应 topic 的评分标准(rubric)。"""
    from app.interview.evaluator import _extract_topic_id

    topic_id = _extract_topic_id(question_id)
    kb = get_kb()
    topic = kb.get(topic_id)
    if topic is None:
        return {"error": f"topic 不存在:{topic_id}"}
    return {
        "topic_id": topic.id,
        "title": topic.title,
        "rubric": topic.rubric,
    }


def save_note(text: str) -> dict[str, Any]:
    """记笔记(演示用,存内存)。"""
    # 实际笔记存在 agent state 的 notes 列表里;这里仅返回确认
    return {"saved": True, "length": len(text), "preview": text[:80]}


# OpenAI tool schemas(传给 LLM 的 tools 参数)
TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "search_knowledge",
            "description": "检索面试知识库,返回与 query 相关的知识条目。用于出题前了解该 topic 的知识脉络。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "检索查询,如 topic id 或关键词"},
                    "top_k": {"type": "integer", "description": "返回条数,默认 4", "default": 4},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_scoring_rubric",
            "description": "查某道题的评分标准(must_have/good_to_have/common_mistakes)。用于评估前明确要点。",
            "parameters": {
                "type": "object",
                "properties": {
                    "question_id": {"type": "string", "description": "题目 id,如 java.concurrency.volatile.recall.1"},
                },
                "required": ["question_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "save_note",
            "description": "记一条学习笔记(如评估反馈、学习建议)。演示用,存内存。",
            "parameters": {
                "type": "object",
                "properties": {
                    "text": {"type": "string", "description": "笔记内容"},
                },
                "required": ["text"],
            },
        },
    },
]

# 工具名 -> 执行函数(同步/异步均可)
TOOL_REGISTRY = {
    "search_knowledge": search_knowledge,
    "get_scoring_rubric": get_scoring_rubric,
    "save_note": save_note,
}


async def execute_tool(name: str, arguments: dict[str, Any]) -> Any:
    """执行工具调用。同步工具直接调,异步工具 await。"""
    fn = TOOL_REGISTRY.get(name)
    if fn is None:
        return {"error": f"未知工具:{name}"}
    try:
        import inspect

        if inspect.iscoroutinefunction(fn):
            return await fn(**arguments)
        return fn(**arguments)
    except Exception as e:
        logger.exception("tool %s failed", name)
        return {"error": str(e)}
