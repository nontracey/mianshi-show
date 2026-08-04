"""RAG 问答接口:/ingest 与 /ask。

/ingest:加载知识库 -> 切分 -> embed -> 入向量库 + 建 BM25 索引。
/ask:检索(向量+BM25+RRF)-> 生成(防幻觉 System Prompt)-> 带来源。
     stream=true 时返回 SSE。

/ask 完整链路(体现"能省则省、该挡就挡"):
护栏拦截(400)→ 向量库空检查(400)→ 语义缓存命中直接返回
(省一次 LLM)→ 混合检索(503/500 区分 LLM 错误与内部错误)
→ 生成 → 写缓存。

错误码约定:400=用户侧问题(输入被拒/库未初始化),
503=LLM 服务不可用(可重试),500=内部异常。
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
    """入库:加载 KB -> 切分 -> embed -> 向量库 + BM25 索引。

    全流程是幂等的:每次 ingest 都会 reset 向量库与 BM25 索引后
    重建,因此可以反复调用(换知识库来源时重新入库)。
    source 参数可指定知识库来源(默认按配置优先级)。
    """
    start = time.monotonic()
    try:
        kb = await load_kb(source=req.source)
        topics = kb.list_topics()
        chunks = split_topics(topics)
        if not chunks:
            # 知识库加载成功但切不出 chunk,属于数据问题,提示用户检查内容
            get_metrics().record_request((time.monotonic() - start) * 1000)
            return ApiResponse.err(code=500, message="切分后无 chunk,检查知识库内容", trace_id=get_trace_id())

        # embed 分批:避免一次请求体过大/API 超限;批大小见 _EMBED_BATCH
        embedder = get_embedder()
        texts = [c.text for c in chunks]
        all_embs: list[list[float]] = []
        for i in range(0, len(texts), _EMBED_BATCH):
            batch = texts[i : i + _EMBED_BATCH]
            embs = await embedder.embed(batch)
            all_embs.extend(embs)

        # 入向量库:先 reset 清空旧数据,保证 ingest 语义是"全量重建"
        store = get_vector_store()
        await store.reset()
        await store.add(chunks, all_embs)

        # 建 BM25 索引:与向量库一样需要与本次 chunks 保持一致,
        # 先 reset 单例再 build,防止残留旧索引导致检索错位
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
    """RAG 问答。默认 hybrid 检索;stream=true 返回 SSE。

    流水线顺序有讲究——便宜的检查先做:
    1. 护栏拦截(纯正则,零成本)→ 2. 向量库空检查(内存判断)
    → 3. 语义缓存(一次 embed + 内存余弦比较,命中则直接返回,
       省下最贵的 LLM 生成)→ 4. 混合检索 → 5. 生成(或 SSE 流)
    → 6. 写缓存。任何一步失败都会 record_request 保证延迟统计完整。

    mode 参数决定检索策略:vector(纯向量)/hybrid(向量+BM25+RRF)/
    hybrid_rerank(混合+重排)。
    """
    start = time.monotonic()

    # guardrails:输入注入检测
    from app.infra.guardrails import detect_prompt_injection, redact_pii

    guard = detect_prompt_injection(req.question)
    if guard.blocked:
        # 日志里的问题先脱敏,防止用户输入中的 PII 明文落日志
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
                # usage 透传原始 token 统计并追加 cache_hit 标记,前端可据此展示"来自缓存"
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
        # 流式分支:直接返回 SSE 生成器。注意流式路径不写语义缓存——
        # token 逐个发出、服务端不聚合完整答案,缓存留给非流式路径。
        return EventSourceResponse(_ask_stream(req, retrieval, mode))

    try:
        data: AskData = await generate(req.question, retrieval)
    except LLMError as e:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(code=503, message=str(e), trace_id=get_trace_id())

    # 写入语义缓存:下次语义相近的问题可直接命中,省掉检索后的 LLM 生成
    # (sources 序列化为 dict 存储,读取时再还原成 Source 模型)
    sources_dict = [s.model_dump() for s in data.sources]
    await cache.put(req.question, q_emb, data.answer, sources_dict, data.usage)

    get_metrics().record_request((time.monotonic() - start) * 1000)
    return ApiResponse.ok(data, trace_id=get_trace_id())


async def _ask_stream(req: AskReq, retrieval: RetrievalResult, mode: str):
    """SSE 流:先发检索事件(含来源),再逐 token 发 answer,最后发 done。

    事件协议:
    - retrieve:首个事件,携带检索模式与来源列表,前端可先渲染参考来源;
    - token:LLM 逐 token 输出,payload 为单个 token 片段;
    - done:正常结束标记 "[DONE]";
    - error:LLM 调用失败时发送(流已开始,无法再返回 HTTP 错误码,
      只能通过事件告知前端)。
    """
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
    """复用 generator 的来源提取逻辑,把检索结果转成对外 Source 模型。"""
    from app.rag.generator import _extract_sources

    return _extract_sources(retrieval.docs)
