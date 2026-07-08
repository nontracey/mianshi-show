"""语义缓存:question -> embedding -> 相似度 > 阈值则命中。

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

SIM_THRESHOLD = 0.95  # cosine 相似度阈值


@dataclass
class CacheEntry:
    question: str
    embedding: list[float]
    answer: str
    sources: list[dict[str, Any]]
    usage: dict[str, Any]
    timestamp: float


class SemanticCache:
    """语义缓存。embed 一次 question,与历史问比较相似度。"""

    def __init__(self, threshold: float = SIM_THRESHOLD) -> None:
        self._entries: list[CacheEntry] = []
        self._threshold = threshold

    async def get(self, question_embedding: list[float]) -> CacheEntry | None:
        """找相似度 > 阈值的历史问。命中返回 entry,否则 None。"""
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
        return len(self._entries)

    def clear(self) -> None:
        self._entries.clear()


def _cosine(a: list[float], b: list[float]) -> float:
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
