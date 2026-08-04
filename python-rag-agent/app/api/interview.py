"""面试接口:/interview/question 与 /interview/evaluate。

出题:直接返回 topic 的 recallPrompts(已人工撰写)。
评估:LLM-as-judge,temperature=0,与「面试智练」同源 rubric。

定位:这两个是"无状态"单点能力接口——出题和评估各自独立可调用,
与 /api/agent/session 的"有状态完整面试流"互补。前端既可以用
Agent 接口跑整场模拟面试,也可以单独调出题/评估做针对性练习。

错误码:topic/question_id 不存在返回 404;评估输入被护栏拦截返回 400。
"""

from __future__ import annotations

import time

from fastapi import APIRouter

from app.infra.observability import get_metrics, get_trace_id
from app.interview import evaluate_answer, generate_questions
from app.schemas import (
    EvaluateData,
    EvaluateReq,
    Evaluation,
    ApiResponse,
    QuestionData,
    QuestionReq,
)

router = APIRouter(prefix="/api/interview")


@router.post("/question", response_model=ApiResponse[QuestionData])
async def question(req: QuestionReq) -> ApiResponse[QuestionData]:
    """按 topic 出题:直接复用知识库人工撰写的 recallPrompts。

    topic 不存在时 generate_questions 抛 ValueError,映射为 404。
    """
    start = time.monotonic()
    try:
        questions = generate_questions(req.topic, difficulty=req.difficulty, count=req.count)
    except ValueError as e:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=404, message=str(e), trace_id=get_trace_id())
    data = QuestionData(questions=questions)
    get_metrics().record_request((time.monotonic() - start) * 1000)
    return ApiResponse.ok(data, trace_id=get_trace_id())


@router.post("/evaluate", response_model=ApiResponse[EvaluateData])
async def evaluate(req: EvaluateReq) -> ApiResponse[EvaluateData]:
    """评估用户回答:LLM-as-Judge 打分 + 四维度明细。

    流程:护栏检测用户输入 → 脱敏日志 → 调 evaluate_answer
    (内部含重试与降级)→ 包装返回。question_id 无效时 404。
    """
    start = time.monotonic()

    # guardrails:输入注入检测 + PII 脱敏日志
    from app.infra.guardrails import detect_prompt_injection, redact_pii

    guard = detect_prompt_injection(req.user_answer)
    if guard.blocked:
        from app.infra.observability import get_trace_id as _tid

        from app.infra.observability import get_metrics as _m
        _m().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=400, message=f"输入被拒:{guard.reason}", trace_id=_tid())

    # 评估日志只记录问题 ID + 脱敏后回答前 60 字符,够排查又不泄露隐私
    import logging as _lg
    _lg.getLogger(__name__).info("evaluate | qid=%s | answer=%.60s", req.question_id, redact_pii(req.user_answer))

    try:
        evaluation: Evaluation = await evaluate_answer(req.question_id, req.user_answer)
    except ValueError as e:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=404, message=str(e), trace_id=get_trace_id())
    data = EvaluateData(evaluation=evaluation)
    get_metrics().record_request((time.monotonic() - start) * 1000)
    return ApiResponse.ok(data, trace_id=get_trace_id())
