"""Agent 状态定义。贯穿 retrieve -> ask -> evaluate -> decide -> followup/advise 全流程。

AgentState 是状态机的"共享黑板":各节点从中读取输入、写入产出,decide 条件边
依据其中的字段路由。用 dataclass 而非 dict,保证字段有类型、可读性好。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.rag.store import ScoredDoc
from app.schemas import Evaluation, Question


@dataclass
class AgentState:
    """模拟面试 Agent 的全流程状态(共享黑板)。

    分组:
      输入参数    topic(考察知识点)/ rounds(最大轮数)/ round(当前轮计数);
      节点产出    retrieved 检索结果、current_question 当前题目、simulated_answer
                  模拟回答、evaluation 评估结果、notes 笔记;
      FC 轨迹     tool_calls 记录 Function Calling 调用(深挖可讲);
      控制        history 对话历史、done 结束标志。
    """

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
