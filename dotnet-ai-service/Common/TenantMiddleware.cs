using System.Collections.Concurrent;

namespace DotnetAiService.Common;

/// <summary>租户上下文:每请求由 TenantMiddleware 设置,全链路用 <see cref="CurrentTenant"/> 取当前租户。
/// 缺省租户 "default"(无 X-Tenant-Id 头时用)。
/// <para>架构位置:多租户隔离的读取端。对应 C 项目 common/TenantContext.java;
/// B 项目在中间件中按请求传递租户。</para>
/// <para>关键设计点:用 AsyncLocal&lt;string&gt; 存储 —— .NET 的 async/await 会切线程,
/// ThreadLocal 在 await 后可能拿错值;AsyncLocal 随 ExecutionContext 跨 await 传递,
/// 保证 KnowledgeBase/RagService 在任何异步深度取到的都是当前请求的租户
/// (与 Java WebFlux 不能用 ThreadLocal 是同一个问题)。</para>
/// </summary>
public static class TenantContext
{
    /// <summary>缺省租户 id:请求没带 X-Tenant-Id 头时落到这个租户。</summary>
    public const string DefaultTenant = "default";

    /// <summary>当前请求的租户 id(AsyncLocal:每个异步调用链独立,互不串扰)。</summary>
    private static readonly AsyncLocal<string?> Current = new();

    /// <summary>读:未设置/空白时回退 "default";写:仅由 TenantMiddleware 调用。</summary>
    public static string CurrentTenant
    {
        get => string.IsNullOrWhiteSpace(Current.Value) ? DefaultTenant : Current.Value!;
        set => Current.Value = value;
    }

    /// <summary>请求结束时清空,防止线程/上下文复用时把租户串给下一个请求。</summary>
    internal static void Clear() => Current.Value = null;
}

/// <summary>租户中间件:从 X-Tenant-Id 头读租户(缺省 default),存 TenantContext + HttpContext.Items。
/// KB / 向量库 / BM25 都按租户隔离(见 KnowledgeBase / RagService)。
/// 在 TraceIdMiddleware 之后执行。
/// <para>架构位置:对应 C 项目 common/TenantFilter.java。多租户隔离在两个环节生效:
/// ① 写入侧 —— /api/ingest 入库时给每个 chunk 打 tenant_id metadata、KB 按租户建独立 topic 表;
/// ② 读取侧 —— 检索/计数时按 TenantContext.CurrentTenant 过滤。
/// 同一进程内不同租户的知识库与向量数据互不可见。</para>
/// </summary>
public class TenantMiddleware
{
    /// <summary>租户标识请求头名称(三语言统一约定)。</summary>
    public const string TenantHeader = "X-Tenant-Id";

    private readonly RequestDelegate _next;

    public TenantMiddleware(RequestDelegate next) => _next = next;

    /// <summary>每请求:解析租户头 → 写入 TenantContext(AsyncLocal)和 HttpContext.Items →
    /// 执行后续管道 → finally 清空 AsyncLocal。</summary>
    public async Task InvokeAsync(HttpContext ctx)
    {
        var tenant = ctx.Request.Headers[TenantHeader].FirstOrDefault();
        if (string.IsNullOrWhiteSpace(tenant)) tenant = TenantContext.DefaultTenant;
        TenantContext.CurrentTenant = tenant;
        ctx.Items["TenantId"] = tenant;
        try
        {
            await _next(ctx);
        }
        finally
        {
            TenantContext.Clear();
        }
    }
}

/// <summary>简单限流中间件:每租户每分钟 N 次(滑动窗口近似,固定窗口实现)。
/// 超限返回 429。N 由 AppOptions.RateLimitPerMinute 配置。
/// <para>架构位置:对应 B 项目 app/infra/ratelimit.py、C 项目的限流实现。</para>
/// <para>关键设计点:固定窗口计数 —— 以"当前分钟起点"为窗口,窗口内计数超过 N 即拒。
/// 已知局限:临界突刺(上一窗口末尾 + 下一窗口开头可能瞬间通过 2N 次),
/// 严格场景需换滑动窗口/令牌桶;本服务为内部工具,固定窗口够用。
/// 状态存静态 ConcurrentDictionary(进程内),多实例部署需换 Redis。</para>
/// </summary>
public class RateLimitMiddleware
{
    private readonly RequestDelegate _next;
    /// <summary>每租户每分钟配额(构造时兜底:配置 ≤0 时用 20)。</summary>
    private readonly int _rpm;
    /// <summary>每租户的计数桶:(窗口起点秒, 窗口内计数)。静态:中间件实例间共享。</summary>
    private static readonly ConcurrentDictionary<string, (long windowStart, int count)> _buckets = new();

    public RateLimitMiddleware(RequestDelegate next, AppOptions opts)
    {
        _next = next;
        _rpm = opts.RateLimitPerMinute > 0 ? opts.RateLimitPerMinute : 20;
    }

    /// <summary>每请求:对当前租户做固定窗口计数,超限直接返回 429 JSON(不走后续管道);
    /// 未超限放行。计数用 AddOrUpdate 原子完成,无锁并发安全。</summary>
    public async Task InvokeAsync(HttpContext ctx)
    {
        var tenant = TenantContext.CurrentTenant;
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var windowStart = now - (now % 60);  // 当前分钟窗口起点
        // 原子地"读-改-写":同一窗口累加计数;跨窗口则重置为 1
        var allow = _buckets.AddOrUpdate(
            tenant,
            _ => (windowStart, 1),
            (_, old) => old.windowStart == windowStart ? (windowStart, old.count + 1) : (windowStart, 1));
        if (allow.count > _rpm)
        {
            // 超限:直接返回 429,响应体保持与 ApiResponse 一致的 code/message/traceId 结构
            ctx.Response.StatusCode = 429;
            ctx.Response.ContentType = "application/json";
            await ctx.Response.WriteAsync($"{{\"code\":429,\"message\":\"限流:每分钟{_rpm}次已用尽\",\"traceId\":\"{TraceIdMiddleware.CurrentTraceId}\"}}");
            return;
        }
        await _next(ctx);
    }
}
