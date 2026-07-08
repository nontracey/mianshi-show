"""FastAPI 入口:挂载路由、中间件(traceId/异常/CORS)、启动预加载。

启动时默认加载样例知识库(便于 /interview 立即可用);全量数据需显式 /api/ingest。
"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

import app
from app.api import agent, interview, ops, rag
from app.config import get_settings
from app.infra.observability import get_metrics, get_trace_id, new_trace_id, set_trace_id
from app.rag.loader import load_kb_sync

logger = logging.getLogger(__name__)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [%(name)s] traceId=%(trace_id)s %(message)s",
)


class TraceIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:  # noqa: D401
        record.trace_id = get_trace_id() or "-"
        return True


for h in logging.getLogger().handlers:
    h.addFilter(TraceIdFilter())


@asynccontextmanager
async def lifespan(api: FastAPI):
    s = get_settings()
    try:
        load_kb_sync(s)  # 启动加载样例,失败不阻塞(可能是远程模式)
        logger.info("启动预加载知识库完成")
    except Exception as e:
        logger.warning("启动预加载知识库失败(可手动 /api/ingest):%s", e)
    yield


def create_app() -> FastAPI:
    api = FastAPI(
        title="AI 面试陪练服务(Python 版)",
        description="RAG 知识问答 + LLM-judge 评估 + LangGraph Agent。三语言(B/C/D)同契约。",
        version=app.__version__,
        lifespan=lifespan,
    )

    api.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @api.middleware("http")
    async def trace_and_metrics(request: Request, call_next):
        new_trace_id()
        start = time.monotonic()

        # 限流:跳过 /health /docs /openapi.json
        path = request.url.path
        if path.startswith("/api/") or path == "/ingest":
            from app.infra.ratelimit import get_limiter

            client_ip = request.client.host if request.client else "unknown"
            has_own_key = "X-LLM-Key" in request.headers
            rl = get_limiter().check(client_ip, has_own_key=has_own_key)
            if not rl.allowed:
                logger.warning("限流拦截:ip=%s path=%s", client_ip, path)
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
            logger.exception("unhandled exception")
            get_metrics().record_request((time.monotonic() - start) * 1000)
            return JSONResponse(
                status_code=500,
                content={
                    "code": 500,
                    "message": f"内部错误:{e}",
                    "data": None,
                    "traceId": get_trace_id(),
                },
            )
        resp.headers["X-Trace-Id"] = get_trace_id()
        return resp

    api.include_router(ops.router)
    api.include_router(rag.router)
    api.include_router(interview.router)
    api.include_router(agent.router)

    return api


app = create_app()
