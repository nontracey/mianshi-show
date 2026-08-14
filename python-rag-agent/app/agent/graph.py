"""Real LangGraph StateGraph for the interview workflow."""
from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Any
from uuid import uuid4

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from app.agent.state import AgentState
from app.agent.tools import TOOL_SCHEMAS, execute_tool
from app.infra.llm import LLMClient, LLMError, get_llm
from app.interview import evaluate_answer, generate_questions
from app.rag.retriever import get_retriever
from app.rag.store import ScoredDoc
from app.schemas import StreamEvent

logger = logging.getLogger(__name__)
SYSTEM_RETRIEVE = "请调用 search_knowledge 工具检索 topic={topic},了解重点后再出题。"
SYSTEM_SIMULATE = "你是三年经验工程师。请回答面试题,允许遗漏但不要编造:\n{question}"
SYSTEM_ADVISE = "请按评估给出三条具体学习建议。score={score},missed={missed},mistakes={mistakes}"


class AgentOrchestrator:
    """Explicit, checkpointed and asynchronously streamable StateGraph."""

    def __init__(self, llm: LLMClient | None = None, checkpointer: Any | None = None) -> None:
        self._llm = llm
        self._checkpointer = checkpointer or InMemorySaver()
        self.graph = self._build_graph().compile(checkpointer=self._checkpointer)

    def _client(self) -> LLMClient:
        if self._llm is None:
            self._llm = get_llm()
        return self._llm

    def _build_graph(self) -> StateGraph:
        graph = StateGraph(AgentState)
        for name, node in (
            ("retrieve", self._retrieve),
            ("ask", self._ask),
            ("simulate", self._simulate),
            ("evaluate", self._evaluate),
            ("decide", self._decide),
            ("advise", self._advise),
        ):
            graph.add_node(name, node)
        graph.add_edge(START, "retrieve")
        graph.add_edge("retrieve", "ask")
        graph.add_edge("ask", "simulate")
        graph.add_edge("simulate", "evaluate")
        graph.add_edge("evaluate", "decide")
        graph.add_conditional_edges("decide", lambda state: state["route"], {
            "followup": "ask", "advise": "advise",
        })
        graph.add_edge("advise", END)
        return graph

    async def run(
        self, topic: str, rounds: int = 1, *, thread_id: str | None = None
    ) -> AsyncIterator[StreamEvent]:
        initial: AgentState = {
            "topic": topic, "rounds": max(1, min(rounds, 10)), "round": 0,
            "retrieved": [], "notes": [], "tool_calls": [], "done": False, "event": None,
        }
        config = {"configurable": {"thread_id": thread_id or str(uuid4())}}
        try:
            async for update in self.graph.astream(initial, config=config, stream_mode="updates"):
                for values in update.values():
                    if isinstance(values, dict) and values.get("event") is not None:
                        yield values["event"]
            snapshot = await self.graph.aget_state(config)
            yield StreamEvent(type="done", payload={
                "rounds_done": snapshot.values.get("round", 0),
                "tool_calls": snapshot.values.get("tool_calls", []),
                "thread_id": config["configurable"]["thread_id"],
            })
        except LLMError as exc:
            yield StreamEvent(type="error", payload=f"模型调用失败:{exc}")
        except Exception as exc:
            logger.exception("agent graph failed")
            yield StreamEvent(type="error", payload=f"Agent 执行失败:{exc}")

    async def _retrieve(self, state: AgentState) -> AgentState:
        docs, tool_call = await self._retrieve_with_tool(self._client(), state["topic"])
        calls = list(state.get("tool_calls", []))
        if tool_call:
            calls.append(tool_call)
        return {"retrieved": docs, "tool_calls": calls, "event": StreamEvent(
            type="retrieve", payload={
                "tool_call": tool_call, "docs_count": len(docs),
                "docs": [{"topic_id": d.metadata.get("topic_id", ""),
                          "title": d.metadata.get("title", ""), "score": round(d.score, 4)}
                         for d in docs[:3]],
            })}

    async def _ask(self, state: AgentState) -> AgentState:
        questions = generate_questions(state["topic"], count=1)
        if not questions:
            raise ValueError(f"topic 无 recallPrompts:{state['topic']}")
        question = questions[0]
        current_round = state.get("round", 0) + 1
        return {"round": current_round, "current_question": question, "event": StreamEvent(
            type="question", payload={"round": current_round,
                "question_id": question.question_id, "prompt": question.prompt,
                "difficulty": question.difficulty})}

    async def _simulate(self, state: AgentState) -> AgentState:
        question = state["current_question"]
        assert question is not None
        answer, _ = await self._client().chat([
            {"role": "system", "content": SYSTEM_SIMULATE.format(question=question.prompt)},
            {"role": "user", "content": "请回答。"},
        ], temperature=0.5)
        return {"simulated_answer": answer, "event": StreamEvent(
            type="answer", payload={"text": answer, "round": state["round"]})}

    async def _evaluate(self, state: AgentState) -> AgentState:
        question = state["current_question"]
        assert question is not None
        evaluation = await evaluate_answer(
            question.question_id, state["simulated_answer"], llm=self._client())
        return {"evaluation": evaluation, "event": StreamEvent(type="evaluate", payload={
            "score": evaluation.score, "hit": evaluation.hit, "missed": evaluation.missed,
            "mistakes": evaluation.mistakes, "feedback": evaluation.feedback,
            "degraded": evaluation.degraded,
        })}

    async def _decide(self, state: AgentState) -> AgentState:
        evaluation = state["evaluation"]
        assert evaluation is not None
        followup = evaluation.score < 70 and state["round"] < state["rounds"]
        return {"route": "followup" if followup else "advise",
                "event": StreamEvent(type="followup", payload={
                    "round": state["round"], "reason": f"score={evaluation.score} < 70,继续追问",
                }) if followup else None}

    async def _advise(self, state: AgentState) -> AgentState:
        evaluation = state["evaluation"]
        assert evaluation is not None
        if not evaluation.missed and not evaluation.mistakes and evaluation.score >= 85:
            advice = "回答已覆盖全部必答点、无明显错误。可继续挑战更高难度场景。"
        else:
            advice, _ = await self._client().chat([
                {"role": "system", "content": SYSTEM_ADVISE.format(
                    score=evaluation.score, missed=evaluation.missed,
                    mistakes=evaluation.mistakes)},
                {"role": "user", "content": "请给学习建议。"},
            ], temperature=0.3)
        saved = await execute_tool("save_note", {"text": advice})
        return {"notes": [*state.get("notes", []), advice], "done": True,
                "event": StreamEvent(type="advise", payload={
                    "advice": advice, "note_saved": saved.get("saved", False)})}

    async def _retrieve_with_tool(
        self, client: LLMClient, topic: str
    ) -> tuple[list[ScoredDoc], dict[str, Any] | None]:
        messages = [
            {"role": "system", "content": SYSTEM_RETRIEVE.format(topic=topic)},
            {"role": "user", "content": f"topic={topic}"},
        ]
        try:
            _, calls, _ = await client.chat_with_tools(
                messages, [TOOL_SCHEMAS[0]], temperature=0.0, tool_choice="required")
        except LLMError:
            _, calls, _ = await client.chat_with_tools(
                messages, [TOOL_SCHEMAS[0]], temperature=0.0, tool_choice="auto")
        if not calls:
            result = await get_retriever().retrieve(topic, mode="hybrid")
            return result.docs, None
        call = calls[0]
        tool_result = await execute_tool(call["name"], call["arguments"])
        result = await get_retriever().retrieve(
            call["arguments"].get("query", topic), mode="hybrid")
        return result.docs, {
            "name": call["name"], "arguments": call["arguments"],
            "result_docs_count": len(tool_result.get("docs", []))
            if isinstance(tool_result, dict) else 0,
        }


_orchestrator: AgentOrchestrator | None = None

def get_orchestrator() -> AgentOrchestrator:
    global _orchestrator
    if _orchestrator is None:
        _orchestrator = AgentOrchestrator()
    return _orchestrator

def reset_orchestrator() -> None:
    global _orchestrator
    _orchestrator = None
