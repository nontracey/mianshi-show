"""Pydantic 请求/响应模型 + 统一封套 ApiResponse。这是三语言(B/C/D)统一契约的数据层。

封套格式见 docs/00-实现方案-总览.md §4:
  { "code": 0, "message": "ok", "data": {...}, "traceId": "uuid" }

设计要点:
  - 所有业务响应都包一层 ApiResponse,code=0 表示成功,非 0 表示业务错误;
    这样 HTTP 层即便返回 200,客户端也能靠 code 区分成败(与三语言保持一致)。
  - traceId 贯穿请求日志,便于排查问题时串联同一次请求的所有日志。

下面的分组按 API 端点组织:
  /health、/api/ingest、/api/ask、/api/interview/question、/api/interview/evaluate、
  /api/agent/session、/api/metrics,以及 SSE 流事件载荷 StreamEvent。
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
    def ok(cls, data: Any, trace_id: str = "") -> ApiResponse[Any]:
        """构造成功封套(code=0)。trace_id 缺省时自动取当前请求的 traceId。"""
        if not trace_id:
            from app.infra.observability import get_trace_id

            trace_id = get_trace_id()
        return cls(code=0, message="ok", data=data, traceId=trace_id)

    @classmethod
    def err(cls, code: int, message: str, trace_id: str = "") -> ApiResponse[Any]:
        """构造错误封套(code 非 0)。trace_id 缺省时自动取当前请求的 traceId。"""
        if not trace_id:
            from app.infra.observability import get_trace_id

            trace_id = get_trace_id()
        return cls(code=code, message=message, data=None, traceId=trace_id)


# ---------- /health ----------
class HealthData(BaseModel):
    """健康检查响应数据。用于探活与部署自检。"""

    status: str = "ok"
    version: str
    llm_model: str
    vector_store: str
    kb_source: str
    llm_reachable: bool  # LLM client 能否初始化(不代表真实调用成功)
    vector_store_ready: bool  # 知识库是否已入库(count>0)


# ---------- /api/ingest ----------
class IngestReq(BaseModel):
    """入库请求。source 可选:显式指定本地路径覆盖默认数据源优先级。"""

    source: str | None = None  # None -> 默认按 KB_CONTENT_URL/PATH/sample 顺序


class IngestData(BaseModel):
    """入库回执:报告本次摄取的规模与内容版本(用于评测复现)。"""

    count: int  # topic 数
    chunks: int  # 切片数
    content_version: str = ""  # 知识库内容版本,benchmark 复现用


# ---------- /api/ask ----------
class AskReq(BaseModel):
    """RAG 问答请求。stream=true 时返回 SSE 流。"""

    question: str
    top_k: int | None = None  # None -> 用配置默认 rag_top_k_final
    stream: bool = False


class Source(BaseModel):
    """答案的来源引用:指回知识库中的 topic,用于可溯源 / 防幻觉展示。"""

    id: str
    topic: str
    score: float
    card_type: str = ""


class AskData(BaseModel):
    """RAG 问答响应:答案 + 来源列表 + token 用量。"""

    answer: str
    sources: list[Source] = Field(default_factory=list)
    usage: dict[str, Any] = Field(default_factory=dict)


# ---------- /api/interview/question ----------
class QuestionReq(BaseModel):
    """出题请求。按 topic 出题,可按难度过滤、指定数量。"""

    topic: str  # topic id,如 java.concurrency.volatile
    difficulty: int | None = None  # None -> 不过滤难度
    count: int = 1


class Question(BaseModel):
    """单个面试题。question_id 形如 <topic_id>.recall.N。"""

    question_id: str
    prompt: str
    difficulty: int


class QuestionData(BaseModel):
    """出题响应:题目列表。"""

    questions: list[Question]


# ---------- /api/interview/evaluate ----------
class EvaluateReq(BaseModel):
    """评估请求:针对某题(question_id)评估候选人的作答(user_answer)。"""

    question_id: str
    user_answer: str
    stream: bool = False


class Evaluation(BaseModel):
    """LLM-as-judge 的结构化评估结果。

    字段对应评估 Prompt 约定的 JSON:score 总分、dimension_scores 四维度分、
    hit/missed 必答点命中与遗漏、mistakes 犯的常见错误、feedback 改进建议。
    """

    score: int  # 0-100
    dimension_scores: dict[str, int] = Field(default_factory=dict)
    hit: list[str] = Field(default_factory=list)
    missed: list[str] = Field(default_factory=list)
    mistakes: list[str] = Field(default_factory=list)
    feedback: str = ""
    degraded: bool = False  # JSON 解析失败降级时为 true


class EvaluateData(BaseModel):
    """评估响应:包一层 evaluation。"""

    evaluation: Evaluation


# ---------- /api/agent/session ----------
class AgentSessionReq(BaseModel):
    """Agent 模拟面试会话请求:指定考察 topic 与轮数。"""

    topic: str
    rounds: int = 1
    session_id: str | None = None


# ---------- /api/metrics ----------
class MetricsData(BaseModel):
    """可观测指标快照:进程内累计的请求/token/缓存命中/延迟统计。"""

    requests_total: int = 0
    tokens_total: int = 0
    cache_hits: int = 0
    cache_misses: int = 0
    cache_hit_rate: float = 0.0
    avg_latency_ms: float = 0.0
    llm_calls: int = 0


# ---------- SSE 事件载荷 ----------
class StreamEvent(BaseModel):
    """SSE 流事件载荷。type 对应 Agent 状态机各节点/阶段:

    retrieve/question/answer/evaluate/followup/advise 为节点事件,
    token 为流式文本片段,done 表示结束,error 表示出错。
    """

    type: Literal["retrieve", "question", "answer", "evaluate", "followup", "advise", "token", "done", "error"]
    payload: Any = None
