"""语义缓存:question -> embedding -> 相似度 > 阈值则命中。

原理:把问题向量化,与历史问题向量比相似度,超过阈值即命中,直接返回历史答案,
省一次 LLM 调用。这是"语义缓存"而非字符串匹配——措辞不同但语义相近的问题
(如"volatile 保证原子性吗"与"volatile 能不能保证原子性")也能命中。

阈值 0.95 偏保守:宁可少命中也不误命中;语义很接近的改写句通常 >0.95,
不同问题通常 <0.85。命中/未命中都上报 metrics,可量化"省 token"收益。

dev 用内存 dict;prod 可切 redis(接口一致)。
命中率记录到 metrics(深挖可讲"省 token"量化)。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

from app.infra.observability import get_metrics

logger = logging.getLogger(__name__)

SIM_THRESHOLD = 0.95  # cosine 相似度阈值(保守,宁缺毋滥)


@dataclass
class CacheEntry:
    """一条缓存记录:原问题 + 其向量 + 答案 + 来源 + token 用量 + 写入时间。"""

    question: str
    embedding: list[float]
    answer: str
    sources: list[dict[str, Any]]
    usage: dict[str, Any]
    timestamp: float


class SemanticCache:
    """语义缓存。embed 一次 question,与历史问比较相似度。

    get 时线性扫描全部历史条目,找相似度最高者,超过阈值才命中。条目数小
    (问答场景)时线性扫描足够;规模大可换向量索引。
    """

    def __init__(self, threshold: float = SIM_THRESHOLD) -> None:
        self._entries: list[CacheEntry] = []
        self._threshold = threshold

    async def get(self, question_embedding: list[float]) -> CacheEntry | None:
        """找相似度 > 阈值的历史问。命中返回 entry,否则 None。

        遍历全部条目取相似度最高者;>=阈值即命中并上报 cache hit,否则记 miss。
        """
        if not self._entries:
            return None
        best: CacheEntry | None = None
        best_score = 0.0
        for e in self._entries:
            score = _cosine(question_embedding, e.embedding)
            if score > best_score:
                best_score = score
                best = e
        if best and best_score >= self._threshold:
            get_metrics().record_cache(hit=True)
            logger.info("缓存命中:score=%.4f, question=%.40s", best_score, best.question)
            return best
        get_metrics().record_cache(hit=False)
        return None

    async def put(
        self,
        question: str,
        question_embedding: list[float],
        answer: str,
        sources: list[dict[str, Any]],
        usage: dict[str, Any],
    ) -> None:
        """写入一条缓存(问题 + 向量 + 答案 + 来源 + 用量)。"""
        self._entries.append(
            CacheEntry(
                question=question,
                embedding=question_embedding,
                answer=answer,
                sources=sources,
                usage=usage,
                timestamp=time.time(),
            )
        )

    def size(self) -> int:
        """当前缓存条目数。"""
        return len(self._entries)

    def clear(self) -> None:
        """清空缓存(知识库更新后可调用以防过时)。"""
        self._entries.clear()


def _cosine(a: list[float], b: list[float]) -> float:
    """余弦相似度;零向量返回 0(避免除零)。"""
    import math

    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a)
    nb = sum(y * y for y in b)
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


_cache: SemanticCache | None = None


def get_cache() -> SemanticCache:
    global _cache
    if _cache is None:
        _cache = SemanticCache()
    return _cache


def reset_cache() -> None:
    global _cache
    _cache = None
