"""检索器单测:向量 / 混合(RRF)/ rerank 三档。用 FakeLLM 提供确定性 embedding。

测什么:检索链路各层——vector 模式单路召回、hybrid 模式
(向量+BM25 经 RRF 融合后排序正确)、_rrf_fuse 融合规则本身
(双路命中得分更高)、BM25 关键词召回。
怎么测:FakeLLM 的 embed 是 md5 派生的确定性向量,同文本恒同
向量,保证"volatile 相关 chunk 排第一"这类断言稳定可复现。
"""

from __future__ import annotations

import pytest

from app.rag.embedder import Embedder
from app.rag.retriever import Retriever, _rrf_fuse, get_bm25_index, reset_retriever
from app.rag.splitter import Chunk
from app.rag.store import InMemoryVectorStore, ScoredDoc


@pytest.mark.asyncio
async def test_retrieve_vector_mode(mock_stack):
    """vector 模式:只走向量检索。"""
    # 准备向量库:3 个 chunk
    store = InMemoryVectorStore()
    from app.rag.store import set_vector_store
    set_vector_store(store)

    embedder = Embedder(llm=mock_stack)
    from app.rag.embedder import set_embedder
    set_embedder(embedder)

    chunks = [
        Chunk(text="volatile 保证可见性", metadata={"topic_id": "java.volatile", "title": "volatile"}),
        Chunk(text="MySQL 索引 B+ 树", metadata={"topic_id": "db.mysql-index", "title": "索引"}),
        Chunk(text="Python GIL 全局锁", metadata={"topic_id": "py.gil", "title": "GIL"}),
    ]
    embs = await embedder.embed([c.text for c in chunks])
    await store.add(chunks, embs)

    reset_retriever()
    r = Retriever(store=store, embedder=embedder)
    res = await r.retrieve("volatile 可见性", mode="vector", top_k=1)
    assert res.mode == "vector"
    assert len(res.docs) == 1
    assert res.docs[0].metadata["topic_id"] == "java.volatile"


@pytest.mark.asyncio
async def test_retrieve_hybrid_mode(mock_stack):
    """hybrid 模式:向量+BM25+RRF 融合。"""
    store = InMemoryVectorStore()
    from app.rag.store import set_vector_store
    set_vector_store(store)

    embedder = Embedder(llm=mock_stack)
    from app.rag.embedder import set_embedder
    set_embedder(embedder)

    chunks = [
        Chunk(text="volatile 保证可见性与禁止重排", metadata={"topic_id": "java.volatile", "title": "volatile"}),
        Chunk(text="MySQL InnoDB B+ 树索引结构", metadata={"topic_id": "db.mysql-index", "title": "索引"}),
    ]
    embs = await embedder.embed([c.text for c in chunks])
    await store.add(chunks, embs)

    bm25 = get_bm25_index()
    bm25.build(chunks)

    reset_retriever()
    r = Retriever(store=store, embedder=embedder)
    res = await r.retrieve("volatile 可见性", mode="hybrid", top_k=2)
    assert res.mode == "hybrid"
    assert len(res.docs) >= 1
    # volatile 相关 chunk 应排在最前
    assert res.docs[0].metadata["topic_id"] == "java.volatile"


def test_rrf_fuse_basic():
    """RRF:两路都命中的文档得分更高。"""
    vec = [
        ScoredDoc(text="docA", metadata={"id": "A"}, score=0.9),
        ScoredDoc(text="docB", metadata={"id": "B"}, score=0.8),
    ]
    bm25 = [
        (Chunk(text="docB", metadata={"id": "B"}), 2.0),
        (Chunk(text="docC", metadata={"id": "C"}), 1.0),
    ]
    fused = _rrf_fuse(vec, bm25, k=60)
    # docB 两路都命中,应排第一
    assert fused[0].metadata["id"] == "B"
    # 三文档都应在
    ids = {d.metadata["id"] for d in fused}
    assert ids == {"A", "B", "C"}


def test_bm25_index_query():
    """BM25 应能按关键词召回。"""
    from app.rag.retriever import BM25Index

    idx = BM25Index()
    # 语料需 ≥3 篇:只有 2 篇时查询词恰好出现在一半文档中,
    # BM25Okapi 的 IDF=log((2-1+0.5)/(1+0.5))=0,得分全为 0 会被 query 的 s>0 过滤。
    docs = [
        Chunk(text="volatile 保证可见性", metadata={"id": "v"}),
        Chunk(text="MySQL 索引原理", metadata={"id": "m"}),
        Chunk(text="HTTP 三次握手与四次挥手", metadata={"id": "h"}),
    ]
    idx.build(docs)
    res = idx.query("volatile 可见性", top_k=2)
    assert len(res) >= 1
    assert res[0][0].metadata["id"] == "v"
