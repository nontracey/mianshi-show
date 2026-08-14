using System.Text.Json;
using DotnetAiService.Common;
using Npgsql;
using Pgvector;
using Pgvector.Npgsql;

namespace DotnetAiService.Services;

public interface IVectorRepository
{
    int Count(string tenantId);
    Task ResetAsync(string tenantId, CancellationToken ct = default);
    Task AddAsync(string tenantId, IReadOnlyList<Chunk> chunks, IReadOnlyList<float[]> embeddings,
        CancellationToken ct = default);
    Task<List<(Chunk chunk, double score)>> QueryAsync(string tenantId, float[] embedding, int topK,
        CancellationToken ct = default);
}

public sealed class MemoryVectorRepository : IVectorRepository
{
    private readonly List<(string tenant, Chunk chunk, float[] embedding)> _rows = new();
    private readonly object _gate = new();
    public int Count(string tenantId) { lock (_gate) return _rows.Count(x => x.tenant == tenantId); }
    public Task ResetAsync(string tenantId, CancellationToken ct = default)
    {
        lock (_gate) _rows.RemoveAll(x => x.tenant == tenantId);
        return Task.CompletedTask;
    }
    public Task AddAsync(string tenantId, IReadOnlyList<Chunk> chunks, IReadOnlyList<float[]> embeddings,
        CancellationToken ct = default)
    {
        if (chunks.Count != embeddings.Count) throw new ArgumentException("chunk/embedding 数量不一致");
        lock (_gate)
            for (var i = 0; i < chunks.Count; i++) _rows.Add((tenantId, chunks[i], embeddings[i]));
        return Task.CompletedTask;
    }
    public Task<List<(Chunk chunk, double score)>> QueryAsync(
        string tenantId, float[] embedding, int topK, CancellationToken ct = default)
    {
        lock (_gate)
            return Task.FromResult(_rows.Where(x => x.tenant == tenantId)
                .Select(x => (x.chunk, score: Cosine(embedding, x.embedding)))
                .OrderByDescending(x => x.score).Take(topK).ToList());
    }
    private static double Cosine(float[] a, float[] b)
    {
        double dot = 0, na = 0, nb = 0;
        for (var i = 0; i < Math.Min(a.Length, b.Length); i++)
        { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.Sqrt(na * nb);
    }
}

public sealed class PgVectorRepository : IVectorRepository, IAsyncDisposable
{
    private readonly NpgsqlDataSource _dataSource;
    private readonly int _dimensions;
    public PgVectorRepository(string connectionString, int dimensions)
    {
        if (string.IsNullOrWhiteSpace(connectionString))
            throw new InvalidOperationException("VectorStore=pgvector 时必须配置 PgVectorConnectionString");
        _dimensions = dimensions;
        var builder = new NpgsqlDataSourceBuilder(connectionString);
        builder.UseVector();
        _dataSource = builder.Build();
        InitializeAsync().GetAwaiter().GetResult();
    }
    private async Task InitializeAsync()
    {
        await using var command = _dataSource.CreateCommand($"""
            CREATE EXTENSION IF NOT EXISTS vector;
            CREATE TABLE IF NOT EXISTS rag_chunks (
              id uuid PRIMARY KEY,
              tenant_id varchar(128) NOT NULL,
              text text NOT NULL,
              metadata jsonb NOT NULL,
              embedding vector({_dimensions}) NOT NULL
            );
            CREATE INDEX IF NOT EXISTS ix_rag_chunks_tenant ON rag_chunks(tenant_id);
            """);
        await command.ExecuteNonQueryAsync();
    }
    public int Count(string tenantId)
    {
        using var command = _dataSource.CreateCommand("SELECT count(*) FROM rag_chunks WHERE tenant_id=$1");
        command.Parameters.AddWithValue(tenantId);
        return Convert.ToInt32(command.ExecuteScalar());
    }
    public async Task ResetAsync(string tenantId, CancellationToken ct = default)
    {
        await using var command = _dataSource.CreateCommand("DELETE FROM rag_chunks WHERE tenant_id=$1");
        command.Parameters.AddWithValue(tenantId);
        await command.ExecuteNonQueryAsync(ct);
    }
    public async Task AddAsync(string tenantId, IReadOnlyList<Chunk> chunks,
        IReadOnlyList<float[]> embeddings, CancellationToken ct = default)
    {
        await using var batch = _dataSource.CreateBatch();
        for (var i = 0; i < chunks.Count; i++)
        {
            var command = new NpgsqlBatchCommand(
                "INSERT INTO rag_chunks(id,tenant_id,text,metadata,embedding) VALUES($1,$2,$3,$4,$5)");
            command.Parameters.AddWithValue(Guid.NewGuid());
            command.Parameters.AddWithValue(tenantId);
            command.Parameters.AddWithValue(chunks[i].Text);
            command.Parameters.AddWithValue(JsonSerializer.Serialize(chunks[i].Metadata));
            command.Parameters.AddWithValue(new Vector(embeddings[i]));
            batch.BatchCommands.Add(command);
        }
        await batch.ExecuteNonQueryAsync(ct);
    }
    public async Task<List<(Chunk chunk, double score)>> QueryAsync(
        string tenantId, float[] embedding, int topK, CancellationToken ct = default)
    {
        await using var command = _dataSource.CreateCommand("""
            SELECT text, metadata, 1 - (embedding <=> $1) AS score
            FROM rag_chunks WHERE tenant_id=$2
            ORDER BY embedding <=> $1 LIMIT $3
            """);
        command.Parameters.AddWithValue(new Vector(embedding));
        command.Parameters.AddWithValue(tenantId);
        command.Parameters.AddWithValue(topK);
        var rows = new List<(Chunk, double)>();
        await using var reader = await command.ExecuteReaderAsync(ct);
        while (await reader.ReadAsync(ct))
        {
            var metadata = JsonSerializer.Deserialize<Dictionary<string, object>>(
                reader.GetString(1), JsonOptions.Default) ?? new();
            rows.Add((new Chunk { Text = reader.GetString(0), Metadata = metadata }, reader.GetDouble(2)));
        }
        return rows;
    }
    public ValueTask DisposeAsync() => _dataSource.DisposeAsync();
}
