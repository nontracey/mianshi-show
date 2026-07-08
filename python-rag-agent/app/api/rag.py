"""RAG 问答接口:/ingest 与 /ask。

/ingest:加载知识库 -> 切分 -> embed -> 入向量库 + 建 BM25 索引。
/ask:检索(向量+BM25+RRF)-> 生成(防幻觉 System Prompt)-> 带来源。
     stream=true 时返回 SSE。
"""

from __future__ import annotations

import json
import logging
import time

from fastapi import APIRouter, Query
from sse_starlette.sse import EventSourceResponse

from app.config import get_settings
from app.infra.llm import LLMError, get_llm
from app.infra.observability import get_metrics, get_trace_id
from app.rag.embedder import get_embedder
from app.rag.generator import generate
from app.rag.loader import load_kb
from app.rag.retriever import RetrievalResult, get_bm25_index, get_retriever, reset_bm25_index
from app.rag.splitter import split_topics
from app.rag.store import get_vector_store, reset_vector_store
from app.schemas import AskData, AskReq, IngestData, IngestReq, ApiResponse, Source, StreamEvent

router = APIRouter(prefix="/api")
logger = logging.getLogger(__name__)

_EMBED_BATCH = 64  # ingest 时每批 embed 的 chunk 数


@router.post("/ingest", response_model=ApiResponse[IngestData])
async def ingest(req: IngestReq) -> ApiResponse[IngestData]:
    """入库:加载 KB -> 切分 -> embed -> 向量库 + BM25 索引。"""
    start = time.monotonic()
    try:
        kb = await load_kb(source=req.source)
        topics = kb.list_topics()
        chunks = split_topics(topics)
        if not chunks:
            get_metrics().record_request((time.monotonic() - start) * 1000)
            return ApiResponse.err(code=500, message="切分后无 chunk,检查知识库内容", trace_id=get_trace_id())

        # embed 分批
        embedder = get_embedder()
        texts = [c.text for c in chunks]
        all_embs: list[list[float]] = []
        for i in range(0, len(texts), _EMBED_BATCH):
            batch = texts[i : i + _EMBED_BATCH]
            embs = await embedder.embed(batch)
            all_embs.extend(embs)

        # 入向量库
        store = get_vector_store()
        await store.reset()
        await store.add(chunks, all_embs)

        # 建 BM25 索引
        reset_bm25_index()
        bm25 = get_bm25_index()
        bm25.build(chunks)

        data = IngestData(
            count=kb.count(),
            chunks=len(chunks),
            content_version=kb.content_version,
        )
        get_metrics().record_request((time.monotonic() - start) * 1000)
        logger.info("ingest 完成:topics=%d, chunks=%d", kb.count(), len(chunks))
        return ApiResponse.ok(data, trace_id=get_trace_id())
    except Exception as e:
        logger.exception("ingest failed")
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=500, message=f"入库失败:{e}", trace_id=get_trace_id())


@router.post("/ask", response_model=ApiResponse[AskData])
async def ask(
    req: AskReq,
    mode: str = Query("hybrid", description="vector | hybrid | hybrid_rerank"),
) -> ApiResponse[AskData] | EventSourceResponse:
    """RAG 问答。默认 hybrid 检索;stream=true 返回 SSE。"""
    start = time.monotonic()

    # guardrails:输入注入检测
    from app.infra.guardrails import detect_prompt_injection, redact_pii

    guard = detect_prompt_injection(req.question)
    if guard.blocked:
        logger.warning("输入被拦截:%s | q=%.60s", guard.reason, redact_pii(req.question))
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=400, message=f"输入被拒:{guard.reason}", trace_id=get_trace_id())

    store = get_vector_store()
    if store.count() == 0:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(
            code=400,
            message="向量库为空,请先 POST /api/ingest",
            trace_id=get_trace_id(),
        )

    # 语义缓存:embed question -> 查命中
    from app.infra.cache import get_cache
    from app.rag.embedder import get_embedder

    try:
        embedder = get_embedder()
        q_emb = await embedder.embed_one(req.question)
        cache = get_cache()
        cached = await cache.get(q_emb)
        if cached is not None:
            logger.info("缓存命中,跳过 LLM 调用 | q=%.60s", redact_pii(req.question))
            data = AskData(
                answer=cached.answer,
                sources=[Source(**s) for s in cached.sources],
                usage={**cached.usage, "cache_hit": True},
            )
            get_metrics().record_request((time.monotonic() - start) * 1000)
            return ApiResponse.ok(data, trace_id=get_trace_id())
    except LLMError as e:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=503, message=str(e), trace_id=get_trace_id())

    # 检索
    try:
        retriever = get_retriever()
        retrieval: RetrievalResult = await retriever.retrieve(req.question, top_k=req.top_k, mode=mode)
    except LLMError as e:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=503, message=str(e), trace_id=get_trace_id())
    except Exception as e:
        logger.exception("retrieve failed")
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=500, message=f"检索失败:{e}", trace_id=get_trace_id())

    if req.stream:
        return EventSourceResponse(_ask_stream(req, retrieval, mode))

    try:
        data: AskData = await generate(req.question, retrieval)
    except LLMError as e:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=503, message=str(e), trace_id=get_trace_id())

    # 写入语义缓存
    sources_dict = [s.model_dump() for s in data.sources]
    await cache.put(req.question, q_emb, data.answer, sources_dict, data.usage)

    get_metrics().record_request((time.monotonic() - start) * 1000)
    return ApiResponse.ok(data, trace_id=get_trace_id())


async def _ask_stream(req: AskReq, retrieval: RetrievalResult, mode: str):
    """SSE 流:先发检索事件(含来源),再逐 token 发 answer,最后发 done。"""
    sources = [
        {"id": s.id, "topic": s.topic, "score": s.score, "card_type": s.card_type}
        for s in _sources_from(retrieval)
    ]
    yield {"event": "retrieve", "data": json.dumps({"mode": mode, "sources": sources}, ensure_ascii=False)}

    try:
        stream = await generate(req.question, retrieval, stream=True)
        async for token in stream:
            evt = StreamEvent(type="token", payload=token)
            yield {"event": "token", "data": evt.model_dump_json()}
        yield {"event": "done", "data": "[DONE]"}
    except LLMError as e:
        evt = StreamEvent(type="error", payload=str(e))
        yield {"event": "error", "data": evt.model_dump_json()}


def _sources_from(retrieval: RetrievalResult) -> list[Source]:
    from app.rag.generator import _extract_sources

    return _extract_sources(retrieval.docs)
