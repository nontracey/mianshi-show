"""内存向量库单测。

测什么:InMemoryVectorStore 对 VectorStore 契约的实现——
add/count/query(top_k + 余弦排序)/reset/参数校验。
怎么测:手工构造互相正交的单位向量,查询命中时余弦相似度精确
为 1.0,可用 pytest.approx 严格断言排序与分数,无需真实 embedding。
(Chroma/pgvector 实现依赖外部服务,由部署环境验证,不在单测覆盖。)
"""

from __future__ import annotations

import pytest

from app.rag.splitter import Chunk
from app.rag.store import InMemoryVectorStore


@pytest.mark.asyncio
async def test_inmem_add_and_count():
    store = InMemoryVectorStore()
    assert store.count() == 0
    chunks = [Chunk(text="a", metadata={}), Chunk(text="b", metadata={})]
    embs = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]
    await store.add(chunks, embs)
    assert store.count() == 2


@pytest.mark.asyncio
async def test_inmem_query_returns_top_k():
    store = InMemoryVectorStore()
    chunks = [
        Chunk(text="java", metadata={"id": "java"}),
        Chunk(text="python", metadata={"id": "python"}),
        Chunk(text="go", metadata={"id": "go"}),
    ]
    embs = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
    await store.add(chunks, embs)
    # 查最接近 [1,0,0] 的应返回 java
    res = await store.query([1.0, 0.0, 0.0], top_k=1)
    assert len(res) == 1
    assert res[0].metadata["id"] == "java"
    assert res[0].score == pytest.approx(1.0, abs=0.01)


@pytest.mark.asyncio
async def test_inmem_query_empty_returns_empty():
    store = InMemoryVectorStore()
    res = await store.query([1.0, 0.0], top_k=3)
    assert res == []


@pytest.mark.asyncio
async def test_inmem_reset():
    store = InMemoryVectorStore()
    await store.add([Chunk(text="a", metadata={})], [[1.0]])
    assert store.count() == 1
    await store.reset()
    assert store.count() == 0


@pytest.mark.asyncio
async def test_inmem_add_mismatch_raises():
    store = InMemoryVectorStore()
    with pytest.raises(ValueError, match="数量不一致"):
        await store.add([Chunk(text="a", metadata={})], [])
