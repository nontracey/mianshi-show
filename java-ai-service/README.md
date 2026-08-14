# Java AI service

Java 17, Spring Boot 3.5.16 and Spring AI 1.1.8. Memory mode is for tests/demo.
`app.vector-store=pgvector` uses Spring AI `PgVectorStore` with tenant metadata filters.

```bash
./gradlew test
./gradlew bootRun
```

For pgvector, start the root Compose stack and set `VECTOR_STORE=pgvector`,
`PG_URL`, `PG_USER`, `PG_PASSWORD`, the embedding dimension and
`DB_HEALTH_ENABLED=true`. Set `REDIS_HEALTH_ENABLED=true` only when Redis is part of
the active runtime. Local Docker was
unavailable during the latest verification; see `../docs/CAPABILITY_MATRIX.md`.

The service has Actuator/Micrometer, credential-derived tenant identity, hybrid retrieval and
Spring AI tool callbacks. Durable workflow checkpoints, Redis cache integration, MCP,
complete citation v1 and OTLP export remain gaps.
