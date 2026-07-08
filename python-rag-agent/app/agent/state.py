"""Agent 状态定义。贯穿 retrieve -> ask -> evaluate -> decide -> followup/advise 全流程。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.rag.store import ScoredDoc
from app.schemas import Evaluation, Question


@dataclass
class AgentState:
    topic: str
    rounds: int = 1
    round: int = 0

    # 节点产出
    retrieved: list[ScoredDoc] = field(default_factory=list)
    current_question: Question | None = None
    simulated_answer: str = ""
    evaluation: Evaluation | None = None
    notes: list[str] = field(default_factory=list)

    # Function Calling 轨迹(深挖可讲)
    tool_calls: list[dict[str, Any]] = field(default_factory=list)

    # 控制
    history: list[dict[str, Any]] = field(default_factory=list)
    done: bool = False
