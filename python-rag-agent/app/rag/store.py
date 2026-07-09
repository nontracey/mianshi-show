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
        # 必须先 _ensure:否则进程重启后 collection 尚未懒加载,会误报 0
        # (持久化数据其实在磁盘上),导致重复 ingest / 健康检查误判。
        self._ensure()
        assert self._collection is not None
        return self._collection.count()

    async def reset(self) -> None:
        if self._collection is not None:
            self._client.delete_collection(self._collection_name)  # type: ignore[union-attr]
            self._collection = None
            self._ensure()


class PgVectorStore(VectorStore):
    """pgvector(Postgres)持久化向量库。VECTOR_STORE=pgvector + PGVECTOR_URL 时启用。

    需要一个装了 pgvector 扩展的 Postgres(`PGVECTOR_URL` 配置连接串)。
    已连真实 Postgres 17 + pgvector 0.8.0 验证:add / `<=>` cosine 检索 / count / 跨进程持久化。
    连不上时由 get_vector_store 自动降级到 memory,保证 clone 后仍可跑。
    依赖:psycopg[binary] + pgvector(`uv sync --extra prod`)。
    """

    def __init__(self, dsn: str, dim: int = 512, table: str = "rag_chunks") -> None:
        import psycopg
        from pgvector.psycopg import register_vector

        self._table = table
        self._conn = psycopg.connect(dsn, autocommit=True)
        self._conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        register_vector(self._conn)
        self._conn.execute(
            f"CREATE TABLE IF NOT EXISTS {table} "
            f"(id bigserial PRIMARY KEY, text text, metadata jsonb, embedding vector({dim}))"
        )

    async def add(self, chunks: list[Chunk], embeddings: list[list[float]]) -> None:
        import json

        with self._conn.cursor() as cur:
            for c, e in zip(chunks, embeddings):
                cur.execute(
                    f"INSERT INTO {self._table}(text, metadata, embedding) VALUES (%s, %s, %s)",
                    (c.text, json.dumps(c.metadata), e),
                )

    async def query(self, embedding: list[float], top_k: int = 4) -> list[ScoredDoc]:
        # pgvector `<=>` 是 cosine 距离;相似度 = 1 - 距离
        rows = self._conn.execute(
            f"SELECT text, metadata, 1 - (embedding <=> %s::vector) AS score "
            f"FROM {self._table} ORDER BY embedding <=> %s::vector LIMIT %s",
            (embedding, embedding, top_k),
        ).fetchall()
        return [ScoredDoc(text=r[0], metadata=r[1], score=float(r[2])) for r in rows]

    def count(self) -> int:
        return self._conn.execute(f"SELECT count(*) FROM {self._table}").fetchone()[0]

    async def reset(self) -> None:
        self._conn.execute(f"TRUNCATE {self._table}")


_store: VectorStore | None = None


def get_vector_store() -> VectorStore:
    """单例向量库。按 VECTOR_STORE 切换:memory(默认)/ chroma / pgvector。
    任一后端不可用(缺依赖/连不上 DB)时降级到 memory,保证服务可启动。"""
    global _store
    if _store is not None:
        return _store
    from app.config import get_settings
    s = get_settings()
    vs = s.vector_store.lower()
    if vs == "chroma":
        _store = ChromaVectorStore(persist_path=s.chroma_path)
    elif vs == "pgvector":
        if not s.pgvector_url:
            logger.warning("VECTOR_STORE=pgvector 但未配 PGVECTOR_URL,降级到 memory")
            _store = InMemoryVectorStore()
        else:
            try:
                _store = PgVectorStore(dsn=s.pgvector_url)
            except Exception as e:
                logger.warning("pgvector 初始化失败(%s),降级到 memory", e)
                _store = InMemoryVectorStore()
    else:
        _store = InMemoryVectorStore()
    return _store


def reset_vector_store() -> None:
    global _store
    _store = None


def set_vector_store(store: VectorStore) -> None:
    """测试用:注入 store 覆盖单例。"""
    global _store
    _store = store
