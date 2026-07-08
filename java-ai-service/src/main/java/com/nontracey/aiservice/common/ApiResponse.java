package com.nontracey.aiservice.common;

/** 统一响应封套(ApiResponse),与 B/D 三语言一致。
 * {@code { "code": 0, "message": "ok", "data": {...}, "traceId": "uuid" }} */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(0, "ok", data, traceId);
    }

    public static <T> ApiResponse<T> err(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
