"""LangGraph 风格状态图:retrieve -> ask -> simulate -> evaluate -> decide -> followup/advise。

轻量异步状态机实现(不依赖 langgraph),接口与 LangGraph 一致:
- 节点是 async 函数(state -> state)
- 条件边按 state 字段路由
- run() 是 AsyncIterator,逐节点 yield StreamEvent(供 SSE)

Function Calling:retrieve 节点把 search_knowledge 工具传给 LLM,LLM 返回 tool_call,
执行后结果回传--这是真实的 OpenAI Function Calling。
"""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator

from app.agent.state import AgentState
from app.agent.tools import TOOL_SCHEMAS, execute_tool
from app.config import get_settings
from app.infra.llm import LLMClient, LLMError, get_llm
from app.interview import evaluate_answer, generate_questions
from app.rag.retriever import get_retriever
from app.rag.store import ScoredDoc
from app.schemas import StreamEvent

logger = logging.getLogger(__name__)


SYSTEM_RETRIEVE = (
    "你是技术面试官。即将考察候选人的 topic 是 {topic}。"
    "请先调用 search_knowledge 工具检索该 topic 的知识,了解重点后再出题。"
)
SYSTEM_SIMULATE = (
    "你是一个有 3 年经验的中级工程师,正在参加技术面试。"
    "请用第一人称回答以下面试题,展示你真实水平(可以有遗漏、可以不够深,但不要瞎编):\n题目:{question}"
)
SYSTEM_ADVISE = (
    "你是面试教练。基于候选人的评估结果,给出 3 条具体可执行的学习建议,"
    "重点补足 missed 的必答点。输出中文,用 markdown 列表。\n"
    "评估结果:score={score}, missed={missed}, mistakes={mistakes}"
)


class AgentOrchestrator:
    """Agent 状态机编排器。"""

    def __init__(self, llm: LLMClient | None = None) -> None:
        self._llm = llm

    def _client(self) -> LLMClient:
        if self._llm is None:
            self._llm = get_llm()
        return self._llm

    async def run(self, topic: str, rounds: int = 1) -> AsyncIterator[StreamEvent]:
        """跑一轮模拟面试,SSE 流式推事件。"""
        state = AgentState(topic=topic, rounds=max(1, rounds))
        client = self._client()

        # ---------- 1. retrieve(含 Function Calling)----------
        try:
            retrieved_docs, tool_call = await self._retrieve_with_tool(client, topic)
            state.retrieved = retrieved_docs
            if tool_call:
                state.tool_calls.append(tool_call)
            yield StreamEvent(
                type="retrieve",
                payload={
                    "tool_call": tool_call,
                    "docs_count": len(retrieved_docs),
                    "docs": [
                        {"topic_id": d.metadata.get("topic_id", ""), "title": d.metadata.get("title", ""), "score": round(d.score, 4)}
                        for d in retrieved_docs[:3]
                    ],
                },
            )
        except LLMError as e:
            yield StreamEvent(type="error", payload=f"检索失败:{e}")
            return

        # ---------- 循环:ask -> simulate -> evaluate -> decide ----------
        while not state.done and state.round < state.rounds:
            state.round += 1

            # 2. ask(出题)
            try:
                qs = generate_questions(topic, count=1)
                if not qs:
                    yield StreamEvent(type="error", payload=f"topic 无 recallPrompts:{topic}")
                    return
                state.current_question = qs[0]
            except ValueError as e:
                yield StreamEvent(type="error", payload=str(e))
                return
            yield StreamEvent(
                type="question",
                payload={
                    "round": state.round,
                    "question_id": state.current_question.question_id,
                    "prompt": state.current_question.prompt,
                    "difficulty": state.current_question.difficulty,
                },
            )

            # 3. simulate_answer(LLM 模拟求职者回答)
            try:
                sim_msg = [
                    {"role": "system", "content": SYSTEM_SIMULATE.format(question=state.current_question.prompt)},
                    {"role": "user", "content": "请回答。"},
                ]
                answer, _ = await client.chat(sim_msg, temperature=0.5)
                state.simulated_answer = answer
            except LLMError as e:
                yield StreamEvent(type="error", payload=f"模拟回答失败:{e}")
                return
            yield StreamEvent(type="answer", payload={"text": answer, "round": state.round})

            # 4. evaluate(LLM-judge)
            try:
                ev = await evaluate_answer(
                    state.current_question.question_id,
                    answer,
                    llm=client,
                )
                state.evaluation = ev
            except Exception as e:
                yield StreamEvent(type="error", payload=f"评估失败:{e}")
                return
            yield StreamEvent(
                type="evaluate",
                payload={
                    "score": ev.score,
                    "hit": ev.hit,
                    "missed": ev.missed,
                    "mistakes": ev.mistakes,
                    "feedback": ev.feedback,
                    "degraded": ev.degraded,
                },
            )

            # 5. decide(条件边)
            should_followup = ev.score < 70 and state.round < state.rounds
            if should_followup:
                yield StreamEvent(
                    type="followup",
                    payload={
                        "round": state.round,
                        "reason": f"score={ev.score} < 70 且 rounds 未到上限,继续追问",
                    },
                )
                continue

            # 否则进入 advise
            state.done = True

        # ---------- 6. advise(学习建议 + save_note 工具)----------
        if state.evaluation:
            ev = state.evaluation
            # 兜底:回答已满分(无遗漏、无错误)时,不让 LLM 硬凑"通用建议"(实测会跑题成
            # "番茄工作法/GTD"之类废话)。直接给确定性正反馈,省一次 LLM 调用。
            if not ev.missed and not ev.mistakes and ev.score >= 85:
                advice = "回答已覆盖全部必答点、无明显错误,表达清晰。可尝试更高难度题或延伸场景以进一步深化。"
            else:
                try:
                    adv_msg = [
                        {
                            "role": "system",
                            "content": SYSTEM_ADVISE.format(
                                score=ev.score,
                                missed=ev.missed,
                                mistakes=ev.mistakes,
                            ),
                        },
                        {"role": "user", "content": "请给学习建议。"},
                    ]
                    advice, _ = await client.chat(adv_msg, temperature=0.3)
                except LLMError as e:
                    advice = f"(建议生成失败:{e})"

            # 调 save_note 工具(Function Calling 叙事:agent 主动记笔记)
            note_result = await execute_tool("save_note", {"text": advice})
            state.notes.append(advice)
            yield StreamEvent(type="advise", payload={"advice": advice, "note_saved": note_result.get("saved", False)})

        yield StreamEvent(type="done", payload={"rounds_done": state.round, "tool_calls": state.tool_calls})

    async def _retrieve_with_tool(
        self,
        client: LLMClient,
        topic: str,
    ) -> tuple[list[ScoredDoc], dict[str, Any] | None]:
        """retrieve 节点:用 Function Calling 调 search_knowledge 工具。"""
        messages = [
            {"role": "system", "content": SYSTEM_RETRIEVE.format(topic=topic)},
            {"role": "user", "content": f"topic={topic}"},
        ]
        # 只给 search_knowledge 工具,强制 LLM 调它
        tools = [TOOL_SCHEMAS[0]]
        try:
            _content, tool_calls, _usage = await client.chat_with_tools(
                messages, tools, temperature=0.0, tool_choice="required"
            )
        except LLMError:
            # 某些 OpenAI 兼容端点不支持 tool_choice=required,降级 auto
            _content, tool_calls, _usage = await client.chat_with_tools(
                messages, tools, temperature=0.0, tool_choice="auto"
            )

        if not tool_calls:
            # LLM 没调工具,直接用 retriever 检索(降级)
            logger.warning("LLM 未调用 search_knowledge 工具,降级直接检索")
            retriever = get_retriever()
            res = await retriever.retrieve(topic, mode="hybrid")
            return res.docs, None

        tc = tool_calls[0]
        tool_result = await execute_tool(tc["name"], tc["arguments"])

        # 把检索结果转成 ScoredDoc(从 search_knowledge 返回的 docs 重建,或直接再查一次)
        # 这里直接用 retriever 检索一次拿到完整 ScoredDoc(带 embedding score)
        retriever = get_retriever()
        query = tc["arguments"].get("query", topic)
        res = await retriever.retrieve(query, mode="hybrid")
        return res.docs, {
            "name": tc["name"],
            "arguments": tc["arguments"],
            "result_docs_count": len(tool_result.get("docs", [])) if isinstance(tool_result, dict) else 0,
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
