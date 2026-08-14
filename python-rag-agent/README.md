# Python RAG Agent

FastAPI with a real LangGraph 1.2.11 `StateGraph`: typed state, explicit nodes, conditional
edge and checkpointed `thread_id`. The default `InMemorySaver` is deterministic for CI;
a PostgreSQL checkpointer is still required for multi-instance production.

```bash
uv sync --extra dev --locked
uv run ruff check app tests
uv run pytest -q
uv run uvicorn app.main:app
```

`VECTOR_STORE=pgvector` is fail-fast and tenant filtered; it never silently becomes memory.
Install `--extra prod` for its driver. Current verified test counts belong in the capability
matrix, not as a hard-coded claim here.
