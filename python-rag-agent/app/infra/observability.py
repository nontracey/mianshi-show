"""可观测:traceId 注入 + 内存指标聚合。

traceId 贯穿日志(每请求生成);/metrics 暴露累计 token/请求/命中率/延迟。
生产可替换为 Prometheus + LangSmith,接口不变。

架构位置:横切可观测层——
- main.py 中间件在每请求开头 new_trace_id(),并把 traceId 放进日志
  与响应头;异常日志也带同一 traceId,方便串联整条请求链路;
- llm.py 调 LLM 后 record_llm(token 数),cache.py 命中/未命中时
  record_cache,中间件在请求结束 record_request(延迟);
- api/ops.py 的 /metrics 读 snapshot() 对外暴露。

为什么用 ContextVar 而不是全局变量:FastAPI 是异步框架,多个请求
并发执行,ContextVar 保证每个协程拿到自己的 traceId 而互不串扰;
普通全局变量在并发下会被覆盖。
"""

from __future__ import annotations

import time
import uuid
from contextvars import ContextVar

from app.schemas import MetricsData

# 每请求的 traceId 存储:ContextVar 保证异步并发下各请求互不干扰。
# default="" 表示未初始化(如测试环境),日志格式化时回退为 "-"。
_trace_id: ContextVar[str] = ContextVar("trace_id", default="")


def new_trace_id() -> str:
    """生成新的 traceId 并绑定到当前上下文,返回其值(中间件在请求入口调用)。"""
    tid = uuid.uuid4().hex
    _trace_id.set(tid)
    return tid


def get_trace_id() -> str:
    """读取当前上下文的 traceId(日志、响应体、响应头都会用到)。"""
    return _trace_id.get()


def set_trace_id(tid: str) -> None:
    """手动绑定已有 traceId(用于需要延续上游 traceId 的场景,main.py 已导入备用)。"""
    _trace_id.set(tid)


class Metrics:
    """进程内累计指标。线程安全用 GIL 保护简单累加;多 worker 场景需换 Redis。"""

    def __init__(self) -> None:
        # —— 累计计数器:只增不减,snapshot() 时做派生计算 ——
        self.requests_total = 0      # 总请求数(中间件在请求结束时累加)
        self.tokens_total = 0        # 累计消耗 token(llm.py 每次调用后上报)
        self.cache_hits = 0          # 语义缓存命中次数
        self.cache_misses = 0        # 语义缓存未命中次数
        self.llm_calls = 0           # LLM 调用次数(含 chat/chat_json/tools)
        self._latency_sum = 0.0      # 请求延迟总和(毫秒),与 _latency_count 配合算均值
        self._latency_count = 0      # 参与延迟统计的请求数
        self._start = time.monotonic()  # 进程启动时间(预留,可用于计算 QPS)

    def record_request(self, latency_ms: float) -> None:
        """记录一次请求及其延迟(中间件在请求处理完毕时调用)。"""
        self.requests_total += 1
        self._latency_sum += latency_ms
        self._latency_count += 1

    def record_llm(self, tokens: int) -> None:
        """记录一次 LLM 调用与消耗 token 数(llm.py 各入口调用)。"""
        self.llm_calls += 1
        self.tokens_total += tokens

    def record_cache(self, hit: bool) -> None:
        """记录一次语义缓存查询结果(cache.py get 时调用)。"""
        if hit:
            self.cache_hits += 1
        else:
            self.cache_misses += 1

    def snapshot(self) -> MetricsData:
        """生成当前指标快照(/metrics 接口用)。

        派生指标在此计算:缓存命中率 = 命中/(命中+未命中),
        平均延迟 = 延迟总和/请求数;分母为 0 时返回 0 避免除零。
        """
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


# 进程级单例:整个服务共享一份累计指标。
_metrics = Metrics()


def get_metrics() -> Metrics:
    """获取指标单例(懒加载式的简单 DI,测试里可直接构造新 Metrics 替换)。"""
    return _metrics
