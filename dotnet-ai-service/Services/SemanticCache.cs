namespace DotnetAiService.Services;

/// <summary>语义缓存:question 向量与历史向量 cosine 相似度 &gt; 阈值即命中,省一次 LLM 调用。
/// 与 B 同策略(阈值 0.95),内存实现(dev);生产可换 Redis + 向量近邻。
/// <para>架构位置:挂在 /api/ask 端点的"检索之前"(见 Program.cs):
/// 护栏通过后先把问题向量化,命中缓存直接返回,跳过检索 + 生成整条链路。
/// 对应 B 项目 app/infra/cache.py、C 项目 infra/SemanticCache.java。</para>
/// <para>关键设计点 —— 命中判定:不是精确匹配,而是"语义近似":
/// 问题 embedding 与历史问题 embedding 逐一算余弦相似度,≥0.95 即视为同一问题。
/// 阈值权衡:0.95 足够拦下"换个说法问同一件事"(省 token),又不至于把相似但不同的问题混答;
/// 过高(如 0.99)命中率低,过低(如 0.9)会答非所问。</para>
/// <para>使用约束:只对非流式请求生效 —— SSE 流式回答无法缓存复用(Program.cs 中判断);
/// 缓存的是完整响应 payload(answer+sources+usage),命中时原样返回。</para>
/// <para>实现取舍:线性扫描 List,命中判定 O(n) —— 演示规模够用;
/// lock 保护并发读写(singleton 注册,多请求共享)。</para>
/// </summary>
public class SemanticCache
{
    /// <summary>命中阈值:余弦相似度 ≥0.95 判定为同一问题(与 B/C 保持一致)。</summary>
    private const double Threshold = 0.95;
    /// <summary>缓存条目:(问题向量, 完整响应 payload)。只增不删(演示用,无 TTL/淘汰策略)。</summary>
    private readonly List<(float[] Emb, object Payload)> _entries = new();
    /// <summary>并发锁:Get/Put 都在锁内,避免读到写一半的列表。</summary>
    private readonly object _lock = new();

    /// <summary>查缓存:线性扫描所有历史问题向量,首个相似度 ≥阈值 的条目即命中。</summary>
    /// <param name="qEmb">当前问题的 embedding(端点已算好,避免重复调 Embedding)。</param>
    /// <returns>命中返回缓存的 payload;未命中返回 null(调用方继续走检索+生成)。</returns>
    public object? Get(float[] qEmb)
    {
        lock (_lock)
        {
            foreach (var e in _entries)
                if (Cosine(qEmb, e.Emb) >= Threshold)
                    return e.Payload;
            return null;
        }
    }

    /// <summary>写缓存:生成成功后由端点调用,把问题向量与完整响应一起存入。</summary>
    public void Put(float[] qEmb, object payload)
    {
        lock (_lock) { _entries.Add((qEmb, payload)); }
    }

    /// <summary>余弦相似度(与 RagService.Cosine 同式):点积 / 模长乘积。
    /// 维度不一致或零向量返回 0,防御性处理避免异常。</summary>
    private static double Cosine(float[] a, float[] b)
    {
        if (a.Length != b.Length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.Length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.Sqrt(na) * Math.Sqrt(nb));
    }
}
