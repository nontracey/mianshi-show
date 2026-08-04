namespace DotnetAiService.Services;

/// <summary>进程内指标聚合(线程安全,与 B/C 的 Metrics 等价)。
/// <para>架构位置:可观测性的最小实现 —— 各端点在关键路径上打点
/// (请求计数/延迟、LLM 调用与 token、缓存命中),/api/metrics 端点输出快照。
/// 对应 B 项目 app/infra/observability.py 的指标部分、C 项目 infra/Metrics.java。</para>
/// <para>关键设计点:全部用 Interlocked 原子操作,无锁并发安全
/// (singleton 注册,所有请求线程共享同一实例);
/// 延迟用"总和 + 次数"两个计数器算均值,避免存每次请求的原始值占内存。</para>
/// <para>生产演进:这里聚合的指标语义(requests_total/tokens_total/cache_hit_rate 等)
/// 可直接映射到 Prometheus counter/gauge。</para>
/// </summary>
public class Metrics
{
    /// <summary>计数器组:请求数、token 总量、LLM 调用次数、缓存命中/未命中、延迟总和与样本数。
    /// 均为 long,配合 Interlocked 原子读写。</summary>
    private long _requests, _tokens, _llmCalls, _cacheHits, _cacheMisses, _latencySum, _latencyCount;

    /// <summary>记录一次端点请求完成(无论成功失败都记):请求数 +1,并把耗时累入延迟统计。</summary>
    /// <param name="latencyMs">本次请求端到端耗时(毫秒)。</param>
    public void RecordRequest(long latencyMs)
    {
        Interlocked.Increment(ref _requests);
        Interlocked.Add(ref _latencySum, latencyMs);
        Interlocked.Increment(ref _latencyCount);
    }

    /// <summary>记录一次 LLM 生成调用及其 token 消耗(流式请求无 usage,端点记 0)。</summary>
    /// <param name="tokens">本次调用的 total_tokens。</param>
    public void RecordLlm(int tokens)
    {
        Interlocked.Increment(ref _llmCalls);
        Interlocked.Add(ref _tokens, tokens);
    }

    /// <summary>记录一次语义缓存判定结果(命中/未命中都记,用于算命中率)。</summary>
    /// <param name="hit">true=命中缓存,false=未命中。</param>
    public void RecordCache(bool hit)
    {
        if (hit) Interlocked.Increment(ref _cacheHits);
        else Interlocked.Increment(ref _cacheMisses);
    }

    /// <summary>输出指标快照(/api/metrics 返回)。字段名与 B/C 对齐:
    /// requests_total/tokens_total/llm_calls/cache_hits/cache_misses/cache_hit_rate/avg_latency_ms。
    /// 命中率与均值在无样本时返回 0,避免除零。</summary>
    /// <returns>匿名对象,端点直接序列化。</returns>
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
