"""运维接口:/health 与 /metrics。

/health:版本 + 依赖连通性(LLM/向量库/知识库)。
/metrics:累计 token/请求数/命中率/延迟。
"""

from __future__ import annotations

import time

from fastapi import APIRouter

import app
from app.config import get_settings
from app.infra.llm import LLMClient, LLMError, get_llm
from app.infra.observability import get_metrics
from app.rag.loader import get_kb
from app.schemas import HealthData, MetricsData, ApiResponse

router = APIRouter()


@router.get("/health", response_model=ApiResponse[HealthData])
async def health() -> ApiResponse[HealthData]:
    s = get_settings()
    kb = get_kb()

    llm_reachable = False
    try:
        client = get_llm()
        # 不真实调用,只验证 client 能初始化
        llm_reachable = bool(client.model)
    except LLMError:
        llm_reachable = False

    kb_source = (
        s.kb_content_path or s.kb_content_url or str(s.kb_sample_abs_path)
    )

    data = HealthData(
        status="ok",
        version=app.__version__,
        llm_model=s.llm_model,
        vector_store=s.vector_store,
        kb_source=kb_source,
        llm_reachable=llm_reachable,
        vector_store_ready=kb.count() > 0,
    )
    return ApiResponse.ok(data)


@router.get("/api/metrics", response_model=ApiResponse[MetricsData])
async def metrics() -> ApiResponse[MetricsData]:
    return ApiResponse.ok(get_metrics().snapshot())
