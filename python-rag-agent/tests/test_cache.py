"""语义缓存单测。"""

from __future__ import annotations

import pytest

from app.infra.cache import SemanticCache


@pytest.mark.asyncio
async def test_cache_miss_then_hit():
    cache = SemanticCache(threshold=0.95)
    # 第一次:空缓存,miss
    entry = await cache.get([1.0, 0.0, 0.0])
    assert entry is None
    # 写入
    await cache.put(
        question="volatile 保证原子性吗?",
        question_embedding=[1.0, 0.0, 0.0],
        answer="不保证原子性。",
        sources=[{"id": "java.volatile", "topic": "volatile", "score": 0.9, "card_type": "explain"}],
        usage={"total_tokens": 100},
    )
    assert cache.size() == 1
    # 第二次:相同 embedding,hit
    entry = await cache.get([1.0, 0.0, 0.0])
    assert entry is not None
    assert entry.answer == "不保证原子性。"


@pytest.mark.asyncio
async def test_cache_low_similarity_miss():
    cache = SemanticCache(threshold=0.95)
    await cache.put("q1", [1.0, 0.0, 0.0], "a1", [], {})
    # 完全不同的 embedding,miss
    entry = await cache.get([0.0, 0.0, 1.0])
    assert entry is None


@pytest.mark.asyncio
async def test_cache_clear():
    cache = SemanticCache()
    await cache.put("q", [1.0], "a", [], {})
    assert cache.size() == 1
    cache.clear()
    assert cache.size() == 0
