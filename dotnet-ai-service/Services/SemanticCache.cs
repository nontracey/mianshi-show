namespace DotnetAiService.Services;

/// <summary>语义缓存:question 向量与历史向量 cosine 相似度 &gt; 阈值即命中,省一次 LLM 调用。
/// 与 B 同策略(阈值 0.95),内存实现(dev);生产可换 Redis + 向量近邻。</summary>
public class SemanticCache
{
    private const double Threshold = 0.95;
    private readonly List<(float[] Emb, object Payload)> _entries = new();
    private readonly object _lock = new();

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

    public void Put(float[] qEmb, object payload)
    {
        lock (_lock) { _entries.Add((qEmb, payload)); }
    }

    private static double Cosine(float[] a, float[] b)
    {
        if (a.Length != b.Length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.Length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.Sqrt(na) * Math.Sqrt(nb));
    }
}
