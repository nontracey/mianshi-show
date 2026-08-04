namespace DotnetAiService.Common;

/// <summary>统一响应封套(ApiResponse),与 B/C 三语言一致。
/// <code>{ "code": 0, "message": "ok", "data": {...}, "traceId": "uuid" }</code>
/// <para>架构位置:所有 HTTP 端点(Program.cs 中的 minimal API 端点)无论成功还是失败,
/// 都统一返回这个结构 —— 与 B 项目(python-rag-agent)FastAPI 各端点的响应体、
/// C 项目(java-ai-service)的 common/ApiResponse.java 是同一份跨语言契约。
/// 前端/调用方只需要按 code/message/data/traceId 四个字段解析,不用区分语言实现。</para>
/// <para>关键设计点:用 C# record(不可变引用类型)实现,自带值相等语义和 JSON 序列化友好性;
/// 序列化时属性名按 camelCase 输出(见 JsonOptions.Default),与 B/C 的 JSON 字段名完全对齐。</para>
/// </summary>
/// <typeparam name="T">业务数据类型(data 字段);错误响应中 data 为 null。</typeparam>
/// <param name="Code">业务状态码:0 表示成功;非 0 时与 HTTP 语义对齐
/// (400 参数或前置条件错误、404 资源不存在、429 限流、500/503 服务端错误)。</param>
/// <param name="Message">提示信息:成功为 "ok",失败为可读的中文错误描述。</param>
/// <param name="Data">业务数据;失败时为 default(即 null)。</param>
/// <param name="TraceId">全链路追踪 id,由 TraceIdMiddleware 每请求生成,
/// 与响应头 X-Trace-Id 一致,用于跨服务/日志排查。</param>
public record ApiResponse<T>(int Code, string Message, T? Data, string TraceId)
{
    /// <summary>构造成功响应(code=0,message="ok",携带业务数据)。</summary>
    /// <param name="data">业务数据,放进 data 字段。</param>
    /// <param name="traceId">当前请求的 traceId(一般传 TraceIdMiddleware.CurrentTraceId)。</param>
    public static ApiResponse<T> Ok(T data, string traceId) => new(0, "ok", data, traceId);

    /// <summary>构造失败响应(data=null,code/message 由调用方指定)。
    /// 端点层根据异常类型决定 code:参数错 400、资源不存在 404、外部依赖(LLM/Embedding)失败 503、其他 500。</summary>
    /// <param name="code">业务错误码,与 HTTP 语义对齐。</param>
    /// <param name="message">可读的错误描述。</param>
    /// <param name="traceId">当前请求的 traceId。</param>
    public static ApiResponse<T> Err(int code, string message, string traceId) => new(code, message, default, traceId);
}
