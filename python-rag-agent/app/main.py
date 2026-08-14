"""FastAPI 应用入口:挂载路由、注册中间件(traceId/限流/异常/CORS)、启动预加载知识库。

本文件是服务的装配点,职责包括:
  1. 配置全局日志格式,并通过 TraceIdFilter 把当前请求的 traceId 注入每条日志;
  2. create_app() 应用工厂:注册 CORS、统一的 trace_and_metrics HTTP 中间件,
     并挂载 ops/rag/interview/agent 四个路由模块;
  3. lifespan 启动钩子:预加载样例知识库,让 /interview 相关接口开箱即用。

trace_and_metrics 中间件按顺序做三件事:
  - 为每个请求生成新的 traceId(贯穿日志与响应头 X-Trace-Id);
  - 对 /api/* 与 /ingest 做限流(跳过 /health /docs /openapi.json);
  - 捕获未处理异常,统一返回 500 封套,避免把堆栈直接抛给客户端。

启动时默认加载样例知识库(便于 /interview 立即可用);全量数据需显式 /api/ingest。
"""

from __future__ import annotations

import json
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

import app as app_package
from app.api import agent, interview, ops, rag
from app.config import get_settings
from app.infra.observability import get_metrics, get_trace_id, new_trace_id
from app.infra.tenant import reset_tenant, set_tenant
from app.rag.loader import load_kb_sync

logger = logging.getLogger(__name__)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [%(name)s] traceId=%(trace_id)s %(message)s",
)


class TraceIdFilter(logging.Filter):
    """日志过滤器:给每条 LogRecord 注入当前请求的 trace_id 字段。

    配合上面 logging.basicConfig 的 format="...traceId=%(trace_id)s...",
    实现"每条日志自动带上所属请求的 traceId"。无请求上下文时显示 "-"。
    """

    def filter(self, record: logging.LogRecord) -> bool:  # noqa: D401
        record.trace_id = get_trace_id() or "-"
        return True


# 把 TraceIdFilter 挂到 root logger 的所有 handler,保证全局日志都带 traceId。
for h in logging.getLogger().handlers:
    h.addFilter(TraceIdFilter())


@asynccontextmanager
async def lifespan(api: FastAPI):
    """应用生命周期钩子:启动时预加载样例知识库。

    设计考量:预加载让出题/评估类接口开箱即用;但加载失败不应阻塞服务启动
    (例如配置为远程 manifest 模式、或网络受限),因此仅记录 warning,
    用户可随后手动调 /api/ingest 完成入库。
    """
    s = get_settings()
    try:
        load_kb_sync(s)  # 启动加载样例,失败不阻塞(可能是远程模式)
        logger.info("启动预加载知识库完成")
    except Exception as e:
        logger.warning("启动预加载知识库失败(可手动 /api/ingest):%s", e)
    yield


def create_app() -> FastAPI:
    """应用工厂:构建并返回配置完整的 FastAPI 实例。

    步骤:创建实例(挂 lifespan) -> 注册 CORS -> 注册统一 HTTP 中间件 ->
    挂载 ops/rag/interview/agent 路由。用工厂模式便于测试时构造隔离实例。
    """
    api = FastAPI(
        title="AI 面试陪练服务(Python 版)",
        description="RAG 知识问答 + LLM-judge 评估 + LangGraph Agent。三语言(B/C/D)同契约。",
        version=app_package.__version__,
        lifespan=lifespan,
    )

    # CORS:开发/演示场景放开所有来源;生产可按需收紧。
    api.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @api.middleware("http")
    async def trace_and_metrics(request: Request, call_next):
        """统一 HTTP 中间件:traceId 贯穿 + 限流 + 异常兜底 + 指标记录。"""
        # 为每个请求生成新 traceId,后续日志与响应头都基于它。
        new_trace_id()
        start = time.monotonic()
        settings = get_settings()
        api_keys = json.loads(settings.api_key_tenants)
        credential = request.headers.get("X-Api-Key")
        tenant = api_keys.get(credential) if credential else None
        if tenant is None and not settings.allow_anonymous:
            return JSONResponse(status_code=401, content={
                "code": 401, "message": "missing or invalid credential",
                "data": None, "traceId": get_trace_id(),
            })
        tenant_token = set_tenant(tenant or "default")

        # 限流:跳过 /health /docs /openapi.json(这些是探活/文档,不应被限)
        path = request.url.path
        if path.startswith("/api/") or path == "/ingest":
            from app.infra.ratelimit import get_limiter

            client_ip = request.client.host if request.client else "unknown"
            # 自带 X-LLM-Key 的请求走用户自己的额度,不占公共配额,直接放行。
            has_own_key = "X-LLM-Key" in request.headers
            rl = get_limiter().check(client_ip, has_own_key=has_own_key)
            if not rl.allowed:
                logger.warning("限流拦截:ip=%s path=%s", client_ip, path)
                reset_tenant(tenant_token)
                return JSONResponse(
                    status_code=429,
                    content={
                        "code": 429,
                        "message": f"请求过于频繁,每分钟限 {get_limiter()._limit} 次;{rl.reset_in}s 后重试。"
                        "可用 X-LLM-Key 头传自带 Key 绕过公共额度。",
                        "data": None,
                        "traceId": get_trace_id(),
                    },
                    headers={"Retry-After": str(rl.reset_in)},
                )

        try:
            resp = await call_next(request)
        except Exception as e:
            # 未处理异常兜底:记录日志 + 返回 500 封套,避免堆栈泄漏给客户端。
            logger.exception("unhandled exception")
            get_metrics().record_request((time.monotonic() - start) * 1000)
            resp = JSONResponse(
                status_code=500,
                content={
                    "code": 500,
                    "message": f"内部错误:{e}",
                    "data": None,
                    "traceId": get_trace_id(),
                },
            )
        finally:
            reset_tenant(tenant_token)
        # 把 traceId 写回响应头,便于客户端反馈问题时定位日志。
        resp.headers["X-Trace-Id"] = get_trace_id()
        return resp

    # 挂载四个业务路由模块。
    api.include_router(ops.router)
    api.include_router(rag.router)
    api.include_router(interview.router)
    api.include_router(agent.router)

    return api


app = create_app()
