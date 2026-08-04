"""Agent 接口:/api/agent/session(SSE 流式模拟面试)。

事件序列:retrieve -> question -> answer -> evaluate -> (followup -> question -> ... ) -> advise -> done
每个事件是 StreamEvent(type + payload),SSE 推送。

为什么用 SSE:一场模拟面试包含多轮"检索→提问→评估→追问",
整体耗时可达数十秒。SSE 让每个节点一完成就推一个事件,前端可以
实时展示"正在检索知识库/正在评估"的进度,而不是干等最终结果。
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
    """模拟面试 Agent,SSE 推分步事件。

    前置检查向量库:未 ingest 时检索节点必挂,提前 400 告知,
    比让 SSE 流在中途报 error 体验更好。
    返回 EventSourceResponse 后,具体节点事件由 AgentOrchestrator
    状态机逐个 yield(事件类型见 graph.py 节点定义)。
    """
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
        # 在生成器外先捕获 traceId:SSE 生成器在独立任务中执行,
        # ContextVar 可能不延续,提前取出来供 error 事件引用
        trace_id = get_trace_id()
        try:
            async for ev in orchestrator.run(req.topic, rounds=req.rounds):
                yield {"event": ev.type, "data": ev.model_dump_json()}
        except Exception as e:
            # 流已开始后任何异常都转成 error 事件(HTTP 状态码已无法更改)
            yield {
                "event": "error",
                "data": json.dumps({"error": str(e), "traceId": trace_id}, ensure_ascii=False),
            }
        finally:
            # 无论正常结束还是异常,都记录本次会话总耗时
            get_metrics().record_request((time.monotonic() - start) * 1000)

    return EventSourceResponse(event_gen())
