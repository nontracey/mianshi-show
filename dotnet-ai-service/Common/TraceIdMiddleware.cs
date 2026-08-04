using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetAiService.Common;

/// <summary>traceId 中间件:每请求生成 traceId 注入 HttpContext + 响应头。
/// <para>架构位置:管道中最先执行的中间件(Program.cs 里 UseMiddleware 的第一个),
/// 与 C 项目 common/TraceIdFilter.java 对应(B 项目在 app/infra/observability.py 中实现同等能力)。
/// 后续所有响应封套 ApiResponse 的 traceId 字段、限流 429 响应体都从这里取值。</para>
/// <para>关键设计点:用 AsyncLocal 而非 ThreadLocal 存 traceId —— async/await 会切换线程,
/// ThreadLocal 在 await 之后可能拿到错误值;AsyncLocal 随 ExecutionContext 流动,
/// 跨 await、跨端点内所有异步调用都能正确取到当前请求的 traceId。</para>
/// </summary>
public class TraceIdMiddleware
{
    private readonly RequestDelegate _next;
    /// <summary>当前请求的 traceId(AsyncLocal:跨 await 正确传递,每个请求互不串扰)。</summary>
    private static readonly AsyncLocal<string?> Current = new();

    public TraceIdMiddleware(RequestDelegate next) => _next = next;

    /// <summary>每请求:生成 32 位无横线 GUID → 写入 AsyncLocal + HttpContext.Items + 响应头,
    /// 然后执行管道后续部分;无论成功失败,finally 里清空 AsyncLocal 防止线程复用时串值。</summary>
    public async Task InvokeAsync(HttpContext ctx)
    {
        var traceId = Guid.NewGuid().ToString("N");
        Current.Value = traceId;
        ctx.Items["TraceId"] = traceId;
        // 在 _next 前设 header(response 还没 started);放 finally 会因 "Headers are read-only" 抛异常
        ctx.Response.Headers["X-Trace-Id"] = traceId;
        try
        {
            await _next(ctx);
        }
        finally
        {
            Current.Value = null;
        }
    }

    /// <summary>静态便捷入口:任意代码(端点、服务)都可直接取当前请求的 traceId;
    /// 不在请求上下文中时返回 "-",避免 null。</summary>
    public static string CurrentTraceId => Current.Value ?? "-";
}

/// <summary>全局 JSON 序列化选项:所有端点 Results.Json(...) 统一传 Default,
/// 保证三语言响应字段命名一致(camelCase)、null 字段不输出(与 B/C 行为对齐)、紧凑无缩进。</summary>
public static class JsonOptions
{
    /// <summary>默认序列化配置:camelCase 属性名(如 question_id 保持原样、TraceId → traceId)、
    /// 忽略 null、不缩进。</summary>
    public static readonly JsonSerializerOptions Default = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false,
    };
}
