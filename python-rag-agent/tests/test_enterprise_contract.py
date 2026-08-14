from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.agent.graph import AgentOrchestrator
from app.infra.tenant import reset_tenant, set_tenant
from app.rag.splitter import Chunk
from app.rag.store import InMemoryVectorStore


def test_agent_is_real_compiled_langgraph(fake_llm):
    orchestrator = AgentOrchestrator(llm=fake_llm)
    assert orchestrator.graph.__class__.__module__.startswith("langgraph.")


@pytest.mark.asyncio
async def test_vector_store_isolates_tenants():
    store = InMemoryVectorStore()
    token = set_tenant("tenant-a")
    await store.add([Chunk("secret-a", {"chunk_id": "a"})], [[1.0, 0.0]])
    reset_tenant(token)

    token = set_tenant("tenant-b")
    await store.add([Chunk("secret-b", {"chunk_id": "b"})], [[0.0, 1.0]])
    docs = await store.query([1.0, 0.0], top_k=10)
    assert [doc.text for doc in docs] == ["secret-b"]
    reset_tenant(token)


def test_shared_manifest_hash_matches_document():
    root = Path(__file__).resolve().parents[2]
    manifest = json.loads((root / "data/knowledge-manifest.json").read_text())
    import hashlib
    document = root / "data" / manifest["documents"][0]["source"]
    assert hashlib.sha256(document.read_bytes()).hexdigest() == manifest["documents"][0]["sha256"]


def test_pgvector_configuration_fails_fast_without_connection(monkeypatch):
    from app.config import get_settings
    from app.rag.store import get_vector_store, reset_vector_store

    monkeypatch.setenv("VECTOR_STORE", "pgvector")
    monkeypatch.setenv("PGVECTOR_URL", "")
    get_settings.cache_clear()
    reset_vector_store()
    with pytest.raises(RuntimeError, match="PGVECTOR_URL"):
        get_vector_store()
    reset_vector_store()
    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_langgraph_checkpoint_retains_completed_state(ingested):
    ingested._text = "volatile 保证可见性。"
    ingested._json = {
        "score": 90,
        "dimension_scores": {},
        "hit_points": ["可见性"],
        "missed": [],
        "mistakes": [],
        "feedback": "正确",
    }
    orchestrator = AgentOrchestrator(llm=ingested)
    thread_id = "tenant-a:session-1"
    events = [
        event async for event in orchestrator.run(
            "java.concurrency.volatile", rounds=1, thread_id=thread_id
        )
    ]
    snapshot = await orchestrator.graph.aget_state(
        {"configurable": {"thread_id": thread_id}}
    )
    assert events[-1].type == "done"
    assert snapshot.values["done"] is True
    assert snapshot.values["round"] == 1
