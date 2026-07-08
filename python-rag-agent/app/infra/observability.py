"""可观测:traceId 注入 + 内存指标聚合。

traceId 贯穿日志(每请求生成);/metrics 暴露累计 token/请求/命中率/延迟。
生产可替换为 Prometheus + LangSmith,接口不变。
"""

from __future__ import annotations

import time
import uuid
from contextvars import ContextVar

from app.schemas import MetricsData

_trace_id: ContextVar[str] = ContextVar("trace_id", default="")


def new_trace_id() -> str:
    tid = uuid.uuid4().hex
    _trace_id.set(tid)
    return tid


def get_trace_id() -> str:
    return _trace_id.get()


def set_trace_id(tid: str) -> None:
    _trace_id.set(tid)


class Metrics:
    """进程内累计指标。线程安全用 GIL 保护简单累加;多 worker 场景需换 Redis。"""

    def __init__(self) -> None:
        self.requests_total = 0
        self.tokens_total = 0
        self.cache_hits = 0
        self.cache_misses = 0
        self.llm_calls = 0
        self._latency_sum = 0.0
        self._latency_count = 0
        self._start = time.monotonic()

    def record_request(self, latency_ms: float) -> None:
        self.requests_total += 1
        self._latency_sum += latency_ms
        self._latency_count += 1

    def record_llm(self, tokens: int) -> None:
        self.llm_calls += 1
        self.tokens_total += tokens

    def record_cache(self, hit: bool) -> None:
        if hit:
            self.cache_hits += 1
        else:
            self.cache_misses += 1

    def snapshot(self) -> MetricsData:
        hit_rate = 0.0
        total = self.cache_hits + self.cache_misses
        if total:
            hit_rate = round(self.cache_hits / total, 4)
        avg = 0.0
        if self._latency_count:
            avg = round(self._latency_sum / self._latency_count, 2)
        return MetricsData(
            requests_total=self.requests_total,
            tokens_total=self.tokens_total,
            cache_hits=self.cache_hits,
            cache_misses=self.cache_misses,
            cache_hit_rate=hit_rate,
            avg_latency_ms=avg,
            llm_calls=self.llm_calls,
        )


_metrics = Metrics()


def get_metrics() -> Metrics:
    return _metrics
