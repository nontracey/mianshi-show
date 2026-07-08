"""检索器:向量 + BM25 + RRF 融合 + Rerank。

策略(见 docs/01 §3.1):
1. 向量检索 top_k_vector(默认 8)
2. BM25 检索 top_k_vector(rank_bm25,中文按字符切分 -- 简单但有效;M3 可换 jieba)
3. RRF 融合两路排序,k=60
4. (可选)Rerank:对融合后 top_n 用 CrossEncoder 精排,取 top_k_final

每一步可开关,M3 benchmark 对比 纯向量 vs 混合 vs 混合+rerank。
"""

from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass
from typing import Any

from app.config import get_settings
from app.rag.embedder import Embedder, get_embedder
from app.rag.splitter import Chunk
from app.rag.store import ScoredDoc, VectorStore, get_vector_store

logger = logging.getLogger(__name__)


@dataclass
class RetrievalResult:
    docs: list[ScoredDoc]
    mode: str  # "vector" | "hybrid" | "hybrid_rerank"


def _bm25_tokenize(text: str) -> list[str]:
    """中文按字符 + 英文按词的简易分词。避免 jieba 依赖。

    对中文检索效果不如 jieba,但零依赖且对短文本尚可;M3 深挖可对比。
    """
    tokens: list[str] = []
    buf = ""
    for ch in text:
        if "一" <= ch <= "鿿":
            if buf:
                tokens.append(buf.lower())
                buf = ""
            tokens.append(ch)
        elif ch.isalnum():
            buf += ch
        else:
            if buf:
                tokens.append(buf.lower())
                buf = ""
    if buf:
        tokens.append(buf.lower())
    return tokens


class BM25Index:
    """内存 BM25 索引。基于 rank-bm25;懒加载,未装则用纯 Python 实现的简易版。"""

    def __init__(self) -> None:
        self._docs: list[Chunk] = []
        self._tokenized: list[list[str]] = []
        self._bm25 = None
        self._fallback = False

    def build(self, docs: list[Chunk]) -> None:
        self._docs = docs
        self._tokenized = [_bm25_tokenize(d.text) for d in docs]
        try:
            from rank_bm25 import BM25Okapi  # type: ignore

            self._bm25 = BM25Okapi(self._tokenized)
        except ImportError:
            logger.warning("rank-bm25 未安装,BM25 降级为纯 Python TF-IDF 式排序")
            self._fallback = True

    def query(self, q: str, top_k: int) -> list[tuple[Chunk, float]]:
        if not self._docs:
            return []
        q_tokens = _bm25_tokenize(q)
        if self._bm25 is not None:
            scores = self._bm25.get_scores(q_tokens)
        else:
            scores = self._fallback_scores(q_tokens)
        ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)[:top_k]
        return [(self._docs[i], float(s)) for i, s in ranked if s > 0]

    def _fallback_scores(self, q_tokens: list[str]) -> list[float]:
        """纯 Python TF 式打分(无 IDF,粗排)。仅在 rank-bm25 缺失时用。"""
        out = []
        for doc_tokens in self._tokenized:
            if not doc_tokens:
                out.append(0.0)
                continue
            tf = sum(doc_tokens.count(t) for t in q_tokens)
            out.append(tf / len(doc_tokens))
        return out


_bm25_index: BM25Index | None = None


def get_bm25_index() -> BM25Index:
    global _bm25_index
    if _bm25_index is None:
        _bm25_index = BM25Index()
    return _bm25_index


def reset_bm25_index() -> None:
    global _bm25_index
    _bm25_index = None


def _rrf_fuse(
    vector_results: list[ScoredDoc],
    bm25_results: list[tuple[Chunk, float]],
    k: int = 60,
) -> list[ScoredDoc]:
    """Reciprocal Rank Fusion:score = Σ 1/(k + rank_i)。两路结果按 chunk 全文去重合并。"""
    scores: dict[str, float] = {}
    docs_by_key: dict[str, ScoredDoc] = {}

    def _key(text: str) -> str:
        # 用全文 hash 作去重 key:前 N 字相同但内容不同的 chunk 不会被误并。
        return hashlib.md5(text.encode("utf-8")).hexdigest()

    for rank, d in enumerate(vector_results):
        key = _key(d.text)
        scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank + 1)
        docs_by_key[key] = d
    for rank, (chunk, _s) in enumerate(bm25_results):
        key = _key(chunk.text)
        scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank + 1)
        if key not in docs_by_key:
            docs_by_key[key] = ScoredDoc(text=chunk.text, metadata=chunk.metadata, score=0.0)

    fused = [(doc, scores[key]) for key, doc in docs_by_key.items()]
    fused.sort(key=lambda x: x[1], reverse=True)
    return [ScoredDoc(text=d.text, metadata=d.metadata, score=s) for d, s in fused]


class Retriever:
    """检索器。默认 hybrid(向量+BM25+RRF);mode 可切换。"""

    def __init__(
        self,
        store: VectorStore | None = None,
        embedder: Embedder | None = None,
    ) -> None:
        self._store = store
        self._embedder = embedder

    def _get_store(self) -> VectorStore:
        if self._store is None:
            self._store = get_vector_store()
        return self._store

    def _get_embedder(self) -> Embedder:
        if self._embedder is None:
            self._embedder = get_embedder()
        return self._embedder

    async def retrieve(
        self,
        question: str,
        *,
        top_k: int | None = None,
        mode: str = "hybrid",  # "vector" | "hybrid" | "hybrid_rerank"
    ) -> RetrievalResult:
        s = get_settings()
        final_k = top_k or s.rag_top_k_final
        vec_k = s.rag_top_k_vector

        embedder = self._get_embedder()
        q_emb = await embedder.embed_one(question)

        store = self._get_store()
        vec_results = await store.query(q_emb, top_k=vec_k)

        if mode == "vector":
            docs = vec_results[:final_k]
            return RetrievalResult(docs=docs, mode="vector")

        bm25 = get_bm25_index()
        bm25_results = bm25.query(question, top_k=vec_k)
        fused = _rrf_fuse(vec_results, bm25_results, k=s.rrf_k)

        if mode == "hybrid":
            return RetrievalResult(docs=fused[:final_k], mode="hybrid")

        if mode == "hybrid_rerank":
            reranked = await _rerank(question, fused[: max(final_k * 3, 10)])
            return RetrievalResult(docs=reranked[:final_k], mode="hybrid_rerank")

        # 未知 mode 降级到 hybrid
        logger.warning("未知 retrieve mode=%s,降级到 hybrid", mode)
        return RetrievalResult(docs=fused[:final_k], mode="hybrid")


async def _rerank(question: str, docs: list[ScoredDoc]) -> list[ScoredDoc]:
    """Rerank:优先用 sentence-transformers CrossEncoder;未装则按原顺序返回。"""
    if not docs:
        return []
    try:
        from sentence_transformers import CrossEncoder  # type: ignore

        model = _get_cross_encoder()
        pairs = [(question, d.text) for d in docs]
        scores = model.predict(pairs)
        ranked = sorted(zip(docs, scores), key=lambda x: x[1], reverse=True)
        return [ScoredDoc(text=d.text, metadata=d.metadata, score=float(s)) for d, s in ranked]
    except ImportError:
        logger.info("sentence-transformers 未装,跳过 rerank(按原 RRF 顺序返回)")
        return docs
    except Exception as e:
        logger.warning("rerank 失败,降级为原顺序:%s", e)
        return docs


_cross_encoder = None


def _get_cross_encoder():
    global _cross_encoder
    if _cross_encoder is None:
        from sentence_transformers import CrossEncoder  # type: ignore

        _cross_encoder = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")
    return _cross_encoder


_retriever: Retriever | None = None


def get_retriever() -> Retriever:
    global _retriever
    if _retriever is None:
        _retriever = Retriever()
    return _retriever


def reset_retriever() -> None:
    global _retriever
    _retriever = None
