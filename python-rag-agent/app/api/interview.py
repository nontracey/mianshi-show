"""面试接口:/interview/question 与 /interview/evaluate。

出题:直接返回 topic 的 recallPrompts(已人工撰写)。
评估:LLM-as-judge,temperature=0,与「面试智练」同源 rubric。
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
    start = time.monotonic()

    # guardrails:输入注入检测 + PII 脱敏日志
    from app.infra.guardrails import detect_prompt_injection, redact_pii

    guard = detect_prompt_injection(req.user_answer)
    if guard.blocked:
        from app.infra.observability import get_trace_id as _tid

        from app.infra.observability import get_metrics as _m
        _m().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=400, message=f"输入被拒:{guard.reason}", trace_id=_tid())

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
