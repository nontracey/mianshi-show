using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetAiService.Common;

/// <summary>traceId 中间件:每请求生成 traceId 注入 HttpContext + 响应头。</summary>
public class TraceIdMiddleware
{
    private readonly RequestDelegate _next;
    private static readonly AsyncLocal<string?> Current = new();

    public TraceIdMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(HttpContext ctx)
    {
        var traceId = Guid.NewGuid().ToString("N");
        Current.Value = traceId;
        ctx.Items["TraceId"] = traceId;
        try
        {
            await _next(ctx);
        }
        finally
        {
            ctx.Response.Headers["X-Trace-Id"] = traceId;
        }
    }

    public static string CurrentTraceId => Current.Value ?? "-";
}

public static class JsonOptions
{
    public static readonly JsonSerializerOptions Default = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false,
    };
}
