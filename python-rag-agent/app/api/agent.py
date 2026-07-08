"""Agent 接口:/api/agent/session(SSE 流式模拟面试)。

事件序列:retrieve -> question -> answer -> evaluate -> (followup -> question -> ... ) -> advise -> done
每个事件是 StreamEvent(type + payload),SSE 推送。
"""

from __future__ import annotations

import json
import time

from fastapi import APIRouter
from sse_starlette.sse import EventSourceResponse

from app.agent.graph import get_orchestrator
from app.config import get_settings
from app.infra.observability import get_metrics, get_trace_id
from app.rag.store import get_vector_store
from app.schemas import AgentSessionReq, ApiResponse

router = APIRouter(prefix="/api")


@router.post("/agent/session")
async def agent_session(req: AgentSessionReq):
    """模拟面试 Agent,SSE 推分步事件。"""
    start = time.monotonic()

    # 前置检查:向量库需已 ingest
    if get_vector_store().count() == 0:
        get_metrics().record_request((time.monotonic() - start) * 1000)
        return ApiResponse.err(
            code=400,
            message="向量库为空,请先 POST /api/ingest",
            trace_id=get_trace_id(),
        )

    orchestrator = get_orchestrator()

    async def event_gen():
        trace_id = get_trace_id()
        try:
            async for ev in orchestrator.run(req.topic, rounds=req.rounds):
                yield {"event": ev.type, "data": ev.model_dump_json()}
        except Exception as e:
            yield {
                "event": "error",
                "data": json.dumps({"error": str(e), "traceId": trace_id}, ensure_ascii=False),
            }
        finally:
            get_metrics().record_request((time.monotonic() - start) * 1000)

    return EventSourceResponse(event_gen())
