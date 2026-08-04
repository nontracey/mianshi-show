package com.nontracey.aiservice.common;

/**
 * 统一响应封套(ApiResponse),三语言(Java / Python / .NET)对外契约一致。
 *
 * <p><b>结构</b>:{@code { "code": 0, "message": "ok", "data": {...}, "traceId": "uuid" }}
 * <ul>
 *   <li>{@code code}:业务码,0 表示成功,非 0 表示错误(如 400/404/500)。</li>
 *   <li>{@code message}:成功为 "ok",失败为错误描述。</li>
 *   <li>{@code data}:业务数据载荷,失败时为 null。</li>
 *   <li>{@code traceId}:本次请求的链路追踪 ID(来自 {@link TraceIdFilter}),便于排查日志。</li>
 * </ul>
 *
 * <p><b>架构位置</b>:common 层,所有 REST Controller(非 SSE 端点)统一返回该类型,
 * 由 {@link GlobalExceptionHandler} 兜底异常时也包装成该结构。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/schemas.py} 中的统一响应模型。
 *
 * @param code    业务码(0=成功)
 * @param message 提示信息(成功="ok")
 * @param data    业务数据(失败时为 null)
 * @param traceId 链路追踪 ID
 * @param <T>     data 载荷类型
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    /**
     * 构造成功响应。
     *
     * @param data    业务数据
     * @param traceId 当前请求 traceId
     */
    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(0, "ok", data, traceId);
    }

    /**
     * 构造失败响应。
     *
     * @param code    业务错误码(如 400/404/500)
     * @param message 错误描述
     * @param traceId 当前请求 traceId
     */
    public static <T> ApiResponse<T> err(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
