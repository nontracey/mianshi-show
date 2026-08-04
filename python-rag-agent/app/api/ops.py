"""运维接口:/health 与 /metrics。

/health:版本 + 依赖连通性(LLM/向量库/知识库)。
/metrics:累计 token/请求数/命中率/延迟。

用途:/health 供部署探针/人工检查服务可用性(注意它是"轻量
探测",不真实调 LLM,避免健康检查本身烧 token);/metrics 供
观察面板/压测脚本读取进程内累计指标。
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
    """健康检查:返回版本与依赖状态。

    各项判定方式:
    - llm_reachable:只验证 LLM client 能初始化(model 非空),
      不发起真实调用,保证探针零成本;
    - vector_store_ready:以知识库条目数 > 0 近似判断
      (KB 已加载即认为检索链路可用);
    - kb_source:展示当前知识库来源(本地路径/URL/内置样例)。
    """
    s = get_settings()
    kb = get_kb()

    llm_reachable = False
    try:
        client = get_llm()
        # 不真实调用,只验证 client 能初始化
        llm_reachable = bool(client.model)
    except LLMError:
        llm_reachable = False

    # 知识库来源按优先级展示:显式路径 > URL > 内置样例路径
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
    """暴露进程内累计指标快照(请求数/token/缓存命中率/平均延迟)。"""
    return ApiResponse.ok(get_metrics().snapshot())
