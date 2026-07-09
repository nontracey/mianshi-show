using System.Collections.Concurrent;

namespace DotnetAiService.Common;

/// <summary>租户上下文:每请求由 TenantMiddleware 设置,全链路用 <see cref="CurrentTenant"/> 取当前租户。
/// 缺省租户 "default"(无 X-Tenant-Id 头时用)。</summary>
public static class TenantContext
{
    public const string DefaultTenant = "default";

    private static readonly AsyncLocal<string?> Current = new();

    public static string CurrentTenant
    {
        get => string.IsNullOrWhiteSpace(Current.Value) ? DefaultTenant : Current.Value!;
        set => Current.Value = value;
    }

    internal static void Clear() => Current.Value = null;
}

/// <summary>租户中间件:从 X-Tenant-Id 头读租户(缺省 default),存 TenantContext + HttpContext.Items。
/// KB / 向量库 / BM25 都按租户隔离(见 KnowledgeBase / RagService)。
/// 在 TraceIdMiddleware 之后执行。</summary>
public class TenantMiddleware
{
    public const string TenantHeader = "X-Tenant-Id";

    private readonly RequestDelegate _next;

    public TenantMiddleware(RequestDelegate next) => _next = next;

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
/// 超限返回 429。N 由 AppOptions.RateLimitPerMinute 配置。</summary>
public class RateLimitMiddleware
{
    private readonly RequestDelegate _next;
    private readonly int _rpm;
    private static readonly ConcurrentDictionary<string, (long windowStart, int count)> _buckets = new();

    public RateLimitMiddleware(RequestDelegate next, AppOptions opts)
    {
        _next = next;
        _rpm = opts.RateLimitPerMinute > 0 ? opts.RateLimitPerMinute : 20;
    }

    public async Task InvokeAsync(HttpContext ctx)
    {
        var tenant = TenantContext.CurrentTenant;
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var windowStart = now - (now % 60);  // 当前分钟窗口起点
        var allow = _buckets.AddOrUpdate(
            tenant,
            _ => (windowStart, 1),
            (_, old) => old.windowStart == windowStart ? (windowStart, old.count + 1) : (windowStart, 1));
        if (allow.count > _rpm)
        {
            ctx.Response.StatusCode = 429;
            ctx.Response.ContentType = "application/json";
            await ctx.Response.WriteAsync($"{{\"code\":429,\"message\":\"限流:每分钟{_rpm}次已用尽\",\"traceId\":\"{TraceIdMiddleware.CurrentTraceId}\"}}");
            return;
        }
        await _next(ctx);
    }
}
