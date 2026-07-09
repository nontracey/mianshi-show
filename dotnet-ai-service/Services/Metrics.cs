namespace DotnetAiService.Services;

/// <summary>进程内指标聚合(线程安全,与 B/C 的 Metrics 等价)。</summary>
public class Metrics
{
    private long _requests, _tokens, _llmCalls, _cacheHits, _cacheMisses, _latencySum, _latencyCount;

    public void RecordRequest(long latencyMs)
    {
        Interlocked.Increment(ref _requests);
        Interlocked.Add(ref _latencySum, latencyMs);
        Interlocked.Increment(ref _latencyCount);
    }

    public void RecordLlm(int tokens)
    {
        Interlocked.Increment(ref _llmCalls);
        Interlocked.Add(ref _tokens, tokens);
    }

    public void RecordCache(bool hit)
    {
        if (hit) Interlocked.Increment(ref _cacheHits);
        else Interlocked.Increment(ref _cacheMisses);
    }

    public object Snapshot()
    {
        long h = Interlocked.Read(ref _cacheHits), m = Interlocked.Read(ref _cacheMisses), total = h + m;
        long lc = Interlocked.Read(ref _latencyCount);
        return new
        {
            requests_total = Interlocked.Read(ref _requests),
            tokens_total = Interlocked.Read(ref _tokens),
            llm_calls = Interlocked.Read(ref _llmCalls),
            cache_hits = h,
            cache_misses = m,
            cache_hit_rate = total == 0 ? 0 : Math.Round((double)h / total, 4),
            avg_latency_ms = lc == 0 ? 0 : Math.Round((double)Interlocked.Read(ref _latencySum) / lc, 2),
        };
    }
}
