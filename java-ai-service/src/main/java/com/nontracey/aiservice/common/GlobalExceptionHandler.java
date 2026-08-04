package com.nontracey.aiservice.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器:把 Controller 抛出的未捕获异常统一转换成 {@link ApiResponse} 结构。
 *
 * <p><b>架构位置</b>:common 层横切组件,配合 {@code @RestControllerAdvice} 对所有 {@code @RestController}
 * 生效,避免在每个 Controller 里重复写 try/catch + 错误封装。
 *
 * <p><b>异常映射</b>:
 * <ul>
 *   <li>{@link IllegalArgumentException} -> HTTP 400 / 业务码 400(参数错误,如 topic 不存在)。</li>
 *   <li>其他所有 {@link Exception} -> HTTP 500 / 业务码 500(内部错误)。</li>
 * </ul>
 *
 * <p>注意:部分 Controller(如 InterviewController / RagController)已在方法内自行 catch
 * IllegalArgumentException 并返回业务错误,这里作为兜底,覆盖未被捕获的情况。
 *
 * <p><b>同构映射</b>:对应 B 项目 FastAPI 的 exception_handler / D 项目的中间件异常处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理参数/业务校验类异常(如 topic 不存在、缺少 rubric)。
     *
     * @param e 参数异常
     * @return HTTP 400,业务码 400 的统一响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegal(IllegalArgumentException e) {
        log.warn("参数错误:{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.err(400, e.getMessage(), TraceIdFilter.current()));
    }

    /**
     * 兜底处理所有未被捕获的异常,避免向客户端泄露堆栈细节。
     *
     * @param e 任意异常
     * @return HTTP 500,业务码 500 的统一响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.err(500, "内部错误:" + e.getMessage(), TraceIdFilter.current()));
    }
}
