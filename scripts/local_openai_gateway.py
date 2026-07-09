"""本地 OpenAI 兼容网关:把 /v1/* 转发到智谱 /v4/*,embedding 用本地 bge。

用途:Spring AI / Semantic Kernel 等硬编码 /v1 的客户端,通过本网关接入智谱 glm-4-flash(free)
+ 本地 bge-small-zh embedding(智谱 embedding-2 欠费时的免费替代)。

跑法(用 python-rag-agent 的 venv,里面有 sentence-transformers + bge 模型):
  cd python-rag-agent && uv run python ../scripts/local_openai_gateway.py
  # 或 export OPENAI_API_KEY=智谱key 后跑

默认监听 :8088。C/D 配 OPENAI_BASE_URL=http://localhost:8088/v1。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

# 复用 python-rag-agent 的 bge 模型(避免重复下载)
_MODELS_DIR = Path(__file__).resolve().parent.parent / "python-rag-agent" / "models" / "bge-ms"
_BGE_NAME = str(_MODELS_DIR) if _MODELS_DIR.exists() else "BAAI/bge-small-zh-v1.5"

ZHIPU_BASE = os.getenv("ZHIPU_BASE", "https://open.bigmodel.cn/api/paas/v4")
ZHIPU_KEY = os.getenv("OPENAI_API_KEY", os.getenv("ZHIPU_API_KEY", ""))
CHAT_MODEL = os.getenv("LLM_MODEL", "glm-4-flash")

print(f"[gateway] bge model: {_BGE_NAME}", flush=True)
print(f"[gateway] zhipu base: {ZHIPU_BASE}, chat model: {CHAT_MODEL}", flush=True)
print(f"[gateway] zhipu key: {'set' if ZHIPU_KEY else 'MISSING'}", flush=True)

try:
    from sentence_transformers import SentenceTransformer
    _bge = SentenceTransformer(_BGE_NAME)
    print(f"[gateway] bge loaded, dim={_bge.get_sentence_embedding_dimension()}", flush=True)
except Exception as e:
    print(f"[gateway] bge load failed: {e}", flush=True)
    _bge = None

app = FastAPI(title="local OpenAI compat gateway")


@app.get("/health")
async def health():
    return {"status": "ok", "bge_loaded": _bge is not None, "zhipu_key_set": bool(ZHIPU_KEY)}


@app.post("/v1/chat/completions")
async def chat_completions(req: Request):
    body = await req.json()
    body["model"] = body.get("model", CHAT_MODEL)
    stream = body.get("stream", False)

    headers = {"Authorization": f"Bearer {ZHIPU_KEY}", "Content-Type": "application/json"}

    if stream:
        async def gen():
            async with httpx.AsyncClient(timeout=120, trust_env=False) as c:
                async with c.stream("POST", f"{ZHIPU_BASE}/chat/completions", json=body, headers=headers) as r:
                    async for line in r.aiter_lines():
                        if line:
                            yield line + "\n"
        return StreamingResponse(gen(), media_type="text/event-stream")

    async with httpx.AsyncClient(timeout=120, trust_env=False) as c:
        r = await c.post(f"{ZHIPU_BASE}/chat/completions", json=body, headers=headers)
    return JSONResponse(r.json(), status_code=r.status_code)


@app.post("/v1/embeddings")
async def embeddings(req: Request):
    if _bge is None:
        return JSONResponse({"error": {"message": "bge model not loaded", "type": "server_error"}}, status_code=503)
    body = await req.json()
    inp = body.get("input", "")
    texts = inp if isinstance(inp, list) else [inp]
    embs = _bge.encode(texts, normalize_embeddings=True).tolist()
    return {
        "object": "list",
        "data": [{"object": "embedding", "index": i, "embedding": e} for i, e in enumerate(embs)],
        "model": body.get("model", "bge-small-zh"),
        "usage": {"prompt_tokens": 0, "total_tokens": 0},
    }


@app.get("/v1/models")
async def list_models():
    return {"object": "list", "data": [{"id": CHAT_MODEL, "object": "model"}]}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("GATEWAY_PORT", "8088")), log_level="info")
