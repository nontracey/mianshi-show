# .NET AI service

.NET 8 and Semantic Kernel 1.79.0 with native `KernelFunction` plugins. Agent events are
published through a bounded `IAsyncEnumerable` and `RequestAborted` reaches model calls.

```bash
dotnet restore
dotnet build
dotnet test ../dotnet-ai-service.Tests/DotnetAiService.Tests.csproj
dotnet run
```

`VectorStore=pgvector` selects the Npgsql/Pgvector repository and fails startup when its
connection string is absent. Memory mode is only for demos. The current unit test covers
tenant-scoped query/reset; a real pgvector Testcontainers test remains a documented gap.
