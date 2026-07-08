namespace DotnetAiService.Common;

/// <summary>统一响应封套(ApiResponse),与 B/C 三语言一致。
/// <code>{ "code": 0, "message": "ok", "data": {...}, "traceId": "uuid" }</code></summary>
public record ApiResponse<T>(int Code, string Message, T? Data, string TraceId)
{
    public static ApiResponse<T> Ok(T data, string traceId) => new(0, "ok", data, traceId);
    public static ApiResponse<T> Err(int code, string message, string traceId) => new(code, message, default, traceId);
}
