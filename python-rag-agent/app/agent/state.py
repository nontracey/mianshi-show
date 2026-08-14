"""Typed LangGraph state for the interview workflow."""
from __future__ import annotations

from typing import Any, TypedDict

from app.rag.store import ScoredDoc
from app.schemas import Evaluation, Question, StreamEvent


class AgentState(TypedDict, total=False):
    """Checkpoint-safe state shared by every explicit graph node."""
    topic: str
    rounds: int
    round: int
    retrieved: list[ScoredDoc]
    current_question: Question | None
    simulated_answer: str
    evaluation: Evaluation | None
    notes: list[str]
    tool_calls: list[dict[str, Any]]
    done: bool
    route: str
    event: StreamEvent | None
