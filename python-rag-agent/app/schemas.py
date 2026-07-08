"""Pydantic 请求/响应模型 + 统一封套 ApiResponse。

封套格式见 docs/00-实现方案-总览.md §4:
  { "code": 0, "message": "ok", "data": {...}, "traceId": "uuid" }
"""

from __future__ import annotations

from typing import Any, Generic, Literal, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """统一响应封套。code=0 成功,非 0 错误;traceId 贯穿日志。"""

    code: int = 0
    message: str = "ok"
    data: T | None = None
    traceId: str = ""

    @classmethod
    def ok(cls, data: Any, trace_id: str = "") -> "ApiResponse[Any]":
        if not trace_id:
            from app.infra.observability import get_trace_id

            trace_id = get_trace_id()
        return cls(code=0, message="ok", data=data, traceId=trace_id)

    @classmethod
    def err(cls, code: int, message: str, trace_id: str = "") -> "ApiResponse[Any]":
        if not trace_id:
            from app.infra.observability import get_trace_id

            trace_id = get_trace_id()
        return cls(code=code, message=message, data=None, traceId=trace_id)


# ---------- /health ----------
class HealthData(BaseModel):
    status: str = "ok"
    version: str
    llm_model: str
    vector_store: str
    kb_source: str
    llm_reachable: bool
    vector_store_ready: bool


# ---------- /api/ingest ----------
class IngestReq(BaseModel):
    source: str | None = None  # None -> 默认按 KB_CONTENT_URL/PATH/sample 顺序


class IngestData(BaseModel):
    count: int  # topic 数
    chunks: int  # 切片数
    content_version: str = ""


# ---------- /api/ask ----------
class AskReq(BaseModel):
    question: str
    top_k: int | None = None
    stream: bool = False


class Source(BaseModel):
    id: str
    topic: str
    score: float
    card_type: str = ""


class AskData(BaseModel):
    answer: str
    sources: list[Source] = Field(default_factory=list)
    usage: dict[str, Any] = Field(default_factory=dict)


# ---------- /api/interview/question ----------
class QuestionReq(BaseModel):
    topic: str  # topic id,如 java.concurrency.volatile
    difficulty: int | None = None
    count: int = 1


class Question(BaseModel):
    question_id: str
    prompt: str
    difficulty: int


class QuestionData(BaseModel):
    questions: list[Question]


# ---------- /api/interview/evaluate ----------
class EvaluateReq(BaseModel):
    question_id: str
    user_answer: str
    stream: bool = False


class Evaluation(BaseModel):
    score: int  # 0-100
    dimension_scores: dict[str, int] = Field(default_factory=dict)
    hit: list[str] = Field(default_factory=list)
    missed: list[str] = Field(default_factory=list)
    mistakes: list[str] = Field(default_factory=list)
    feedback: str = ""
    degraded: bool = False  # JSON 解析失败降级时为 true


class EvaluateData(BaseModel):
    evaluation: Evaluation


# ---------- /api/agent/session ----------
class AgentSessionReq(BaseModel):
    topic: str
    rounds: int = 1


# ---------- /api/metrics ----------
class MetricsData(BaseModel):
    requests_total: int = 0
    tokens_total: int = 0
    cache_hits: int = 0
    cache_misses: int = 0
    cache_hit_rate: float = 0.0
    avg_latency_ms: float = 0.0
    llm_calls: int = 0


# ---------- SSE 事件载荷 ----------
class StreamEvent(BaseModel):
    type: Literal["retrieve", "question", "answer", "evaluate", "followup", "advise", "token", "done", "error"]
    payload: Any = None
