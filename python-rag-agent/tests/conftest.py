"""pytest 配置:加载样例知识库,提供 fake LLM(测试不依赖真实 API key)。"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest

# 把项目根加入 sys.path,便于 `import app`
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# 测试隔离:强制用样例知识库,绝不打远程 manifest 或全量本地 KB。
# 否则 /api/ingest 端到端测试会去拉 429 topic(慢且网络不确定,曾致单测跑 43min 且 flaky)。
# 必须在首次 get_settings() 之前设置(env 变量优先级高于 .env)。
import os  # noqa: E402

os.environ["KB_CONTENT_URL"] = ""
os.environ["KB_CONTENT_PATH"] = ""

from app.rag.loader import load_kb_sync, reset_kb  # noqa: E402


@pytest.fixture(autouse=True)
def kb_loaded():
    """每个测试前重载样例知识库(避免上一个测试污染)。"""
    reset_kb()
    load_kb_sync()
    yield
    reset_kb()


class FakeLLM:
    """假 LLM:chat_json 返回预设 JSON;chat 返回预设文本;embed 返回确定性假向量。测试用。"""

    def __init__(
        self,
        json_response: Any | None = None,
        text_response: str = "",
        raise_on_first: Exception | None = None,
        always_raise: Exception | None = None,
        embed_dim: int = 16,
    ) -> None:
        self._json = json_response
        self._text = text_response
        self._raise = raise_on_first  # 仅第一次调用抛
        self._always_raise = always_raise  # 每次调用都抛
        self._call_count = 0
        self.model = "fake-model"
        self._embed_dim = embed_dim
        self._embed_calls = 0

    async def chat_json(self, messages, *, temperature: float = 0.0):
        self._call_count += 1
        if self._always_raise:
            raise self._always_raise
        if self._raise and self._call_count == 1:
            raise self._raise
        return self._json, {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}

    async def chat(self, messages, **kwargs):
        return self._text, {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}

    async def chat_with_tools(self, messages, tools, *, temperature: float = 0.0, tool_choice="auto"):
        """返回预设 tool_call(search_knowledge)。测试 Agent Function Calling。"""
        self._call_count += 1
        # 从最后一条 user 消息提取 query
        last = messages[-1].get("content", "") if messages else ""
        query = "test"
        if "topic=" in last:
            query = last.split("topic=")[-1].strip()
        tool_calls = [{
            "id": "call_fake",
            "name": "search_knowledge",
            "arguments": {"query": query, "top_k": 4},
        }]
        return "", tool_calls, {"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70}

    async def chat_stream(self, messages, *, temperature: float = 0.0):
        for tok in self._text.split():
            yield tok + " "

    async def embed(self, texts: list[str]) -> list[list[float]]:
        self._embed_calls += 1
        import hashlib

        out = []
        for t in texts:
            h = hashlib.md5(t.encode("utf-8")).digest()
            # 用 hash 派生确定性向量;同文本 -> 同向量(测试可复现)
            vec = [(h[i % len(h)] / 255.0 - 0.5) for i in range(self._embed_dim)]
            out.append(vec)
        return out


@pytest.fixture
def fake_llm() -> FakeLLM:
    return FakeLLM()


@pytest.fixture
def mock_stack(fake_llm):
    """全局覆盖 llm/embedder/store 单例(用 fake),供端到端测试用。

    自动 reset,避免污染其他测试。
    """
    from app.infra.llm import reset_llm, set_llm
    from app.rag.embedder import Embedder, reset_embedder, set_embedder
    from app.rag.retriever import reset_bm25_index, reset_retriever
    from app.rag.store import InMemoryVectorStore, reset_vector_store, set_vector_store

    set_llm(fake_llm)
    set_embedder(Embedder(llm=fake_llm))
    set_vector_store(InMemoryVectorStore())
    reset_retriever()
    reset_bm25_index()
    yield fake_llm
    reset_llm()
    reset_embedder()
    reset_vector_store()
    reset_retriever()
    reset_bm25_index()


@pytest.fixture
def ingested(mock_stack):
    """在 mock_stack 基础上,ingest 样例知识库到向量库 + BM25 索引。"""
    import asyncio

    from app.rag.embedder import Embedder
    from app.rag.loader import get_kb
    from app.rag.retriever import get_bm25_index
    from app.rag.splitter import split_topics
    from app.rag.store import get_vector_store

    kb = get_kb()
    chunks = split_topics(kb.list_topics())
    embedder = Embedder(llm=mock_stack)
    embs = asyncio.run(embedder.embed([c.text for c in chunks]))
    asyncio.run(get_vector_store().add(chunks, embs))
    get_bm25_index().build(chunks)
    yield mock_stack
