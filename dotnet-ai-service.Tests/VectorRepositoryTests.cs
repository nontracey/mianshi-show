using DotnetAiService.Services;
using Xunit;

namespace DotnetAiService.Tests;

public class VectorRepositoryTests
{
    [Fact]
    public void PgVectorRequiresExplicitConnectionString()
    {
        var error = Assert.Throws<InvalidOperationException>(
            () => new PgVectorRepository("", 1536));
        Assert.Contains("PgVectorConnectionString", error.Message);
    }

    [Fact]
    public async Task AddRejectsMismatchedChunksAndEmbeddings()
    {
        var repository = new MemoryVectorRepository();
        await Assert.ThrowsAsync<ArgumentException>(() => repository.AddAsync(
            "tenant-a", [new Chunk { Text = "one" }], []));
    }

    [Fact]
    public async Task QueryAndResetAreTenantScoped()
    {
        var repository = new MemoryVectorRepository();
        await repository.AddAsync("tenant-a",
            [new Chunk { Text = "secret-a" }], [new float[] { 1, 0 }]);
        await repository.AddAsync("tenant-b",
            [new Chunk { Text = "secret-b" }], [new float[] { 0, 1 }]);

        var result = await repository.QueryAsync("tenant-b", [1, 0], 10);
        Assert.Single(result);
        Assert.Equal("secret-b", result[0].chunk.Text);

        await repository.ResetAsync("tenant-a");
        Assert.Equal(0, repository.Count("tenant-a"));
        Assert.Equal(1, repository.Count("tenant-b"));
    }
}
