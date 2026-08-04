package com.nontracey.aiservice.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器:为每个请求生成唯一 traceId,注入 MDC 使日志全链路可关联,并回写响应头。
 *
 * <p><b>架构位置</b>:common 层,所有过滤器中优先级最高(HIGHEST_PRECEDENCE),确保后续过滤器
 * (如 {@link TenantFilter})与业务日志都能带上同一个 traceId。
 *
 * <p><b>实现要点</b>:
 * <ul>
 *   <li>traceId 为去掉连字符的 UUID,写入 MDC key {@link #TRACE_ID};application.yml 的日志 pattern
 *       通过 {@code %X{traceId}} 打印。</li>
 *   <li>响应头 {@code X-Trace-Id} 在 chain 执行完后的 finally 中回写。注意:对流式(SSE)响应,
 *       响应可能在 finally 之前就已提交,此时 header 可能无法再写入;普通 JSON 响应不受影响。</li>
 *   <li>{@link #current()} 供 Controller / 异常处理器读取当前 traceId 填进 {@link ApiResponse}。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目的请求级 trace 中间件。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    /** MDC 中 traceId 的 key,也是日志 pattern 里 %X{traceId} 引用的名字。 */
    public static final String TRACE_ID = "traceId";

    /**
     * 生成 traceId -> 写 MDC -> 放行 -> 回写响应头并清理 MDC。
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // 去掉连字符的 UUID,紧凑且唯一
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(TRACE_ID, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            // 回写响应头便于前端/调用方上报排查;SSE 流式响应若已提交则可能不生效
            if (resp instanceof HttpServletResponse http) {
                http.setHeader("X-Trace-Id", traceId);
            }
            // 线程复用,清理 MDC 防止串号
            MDC.remove(TRACE_ID);
        }
    }

    /**
     * 读取当前请求的 traceId(从 MDC)。
     *
     * @return 当前 traceId;非请求线程或未设置时可能为 null
     */
    public static String current() {
        return MDC.get(TRACE_ID);
    }
}
