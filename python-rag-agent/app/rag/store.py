"""向量库抽象 + 实现。

接口:add(docs) / query(embedding, top_k) / count()
实现:
  - InMemoryVectorStore(默认,dev/demo,纯 Python cosine,零依赖)
  - ChromaVectorStore(可选,VECTOR_STORE=chroma 时,持久化到 CHROMA_PATH)
  - PgVectorStore(M6/prod,占位待实现)

InMemory 版刻意不依赖 numpy/chroma,保证网络受限也能跑通。
"""

from __future__ import annotations

import logging
import math
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from app.rag.splitter import Chunk

logger = logging.getLogger(__name__)


@dataclass
class ScoredDoc:
    text: str
    metadata: dict[str, Any]
    score: float


class VectorStore(ABC):
    """向量库抽象。三语言(C/D)同接口。"""

    @abstractmethod
    async def add(self, chunks: list[Chunk], embeddings: list[list[float]]) -> None: ...

    @abstractmethod
    async def query(self, embedding: list[float], top_k: int = 4) -> list[ScoredDoc]: ...

    @abstractmethod
    def count(self) -> int: ...

    @abstractmethod
    async def reset(self) -> None: ...


def _cosine(a: list[float], b: list[float]) -> float:
    """纯 Python cosine 相似度。"""
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


class InMemoryVectorStore(VectorStore):
    """内存向量库。add 时存 (chunk, embedding);query 用 cosine 排序。"""

    def __init__(self) -> None:
        self._docs: list[tuple[Chunk, list[float]]] = []

    async def add(self, chunks: list[Chunk], embeddings: list[list[float]]) -> None:
        if len(chunks) != len(embeddings):
            raise ValueError(f"chunks({len(chunks)}) 与 embeddings({len(embeddings)}) 数量不一致")
        for c, e in zip(chunks, embeddings):
            self._docs.append((c, e))

    async def query(self, embedding: list[float], top_k: int = 4) -> list[ScoredDoc]:
        if not self._docs:
            return []
        scored = [
            ScoredDoc(text=c.text, metadata=c.metadata, score=_cosine(embedding, e))
            for c, e in self._docs
        ]
        scored.sort(key=lambda x: x.score, reverse=True)
        return scored[:top_k]

    def count(self) -> int:
        return len(self._docs)

    async def reset(self) -> None:
        self._docs.clear()


class ChromaVectorStore(VectorStore):
    """Chroma 持久化向量库。VECTOR_STORE=chroma 时启用。

    懒加载 chromadb(import 时才装),避免 dev 环境强制依赖。
    """

    def __init__(self, persist_path: str, collection_name: str = "mianshi_kb") -> None:
        self._persist_path = persist_path
        self._collection_name = collection_name
        self._client = None
        self._collection = None

    def _ensure(self) -> None:
        if self._collection is not None:
            return
        try:
            import chromadb  # type: ignore
        except ImportError as e:
            raise RuntimeError(
                "chromadb 未安装;请 `uv sync --extra rag` 或改用 VECTOR_STORE=memory"
            ) from e
        self._client = chromadb.PersistentClient(path=self._persist_path)
        self._collection = self._client.get_or_create_collection(self._collection_name)

    async def add(self, chunks: list[Chunk], embeddings: list[list[float]]) -> None:
        self._ensure()
        assert self._collection is not None
        if not chunks:
            return
        ids = [f"chunk-{i}" for i in range(self._collection.count(), self._collection.count() + len(chunks))]
        self._collection.add(
            ids=ids,
            documents=[c.text for c in chunks],
            embeddings=embeddings,
            metadatas=[c.metadata for c in chunks],  # type: ignore[arg-type]
        )

    async def query(self, embedding: list[float], top_k: int = 4) -> list[ScoredDoc]:
        self._ensure()
        assert self._collection is not None
        res = self._collection.query(query_embeddings=[embedding], n_results=top_k)
        docs = res.get("documents", [[]])[0]
        metas = res.get("metadatas", [[]])[0]
        dists = res.get("distances", [[]])[0]
        out: list[ScoredDoc] = []
        for d, m, dist in zip(docs, metas, dists):
            # chroma 返回的是距离,转相似度(1 - dist/2 近似 cosine)
            score = 1.0 - dist / 2.0 if dist else 0.0
            out.append(ScoredDoc(text=d, metadata=m or {}, score=score))
        return out

    def count(self) -> int:
        if self._collection is None:
            return 0
        return self._collection.count()

    async def reset(self) -> None:
        if self._collection is not None:
            self._client.delete_collection(self._collection_name)  # type: ignore[union-attr]
            self._collection = None
            self._ensure()


_store: VectorStore | None = None


def get_vector_store() -> VectorStore:
    """单例向量库。按 VECTOR_STORE 切换:memory(默认)/ chroma / pgvector。"""
    global _store
    if _store is not None:
        return _store
    from app.config import get_settings
    s = get_settings()
    vs = s.vector_store.lower()
    if vs == "chroma":
        _store = ChromaVectorStore(persist_path=s.chroma_path)
    elif vs in ("memory", "inmemory", "in-memory"):
        _store = InMemoryVectorStore()
    else:
        # pgvector 等待实现;暂降级到 memory
        logger.warning("VECTOR_STORE=%s 暂未实现,降级到 memory", vs)
        _store = InMemoryVectorStore()
    return _store


def reset_vector_store() -> None:
    global _store
    _store = None


def set_vector_store(store: VectorStore) -> None:
    """测试用:注入 store 覆盖单例。"""
    global _store
    _store = store
