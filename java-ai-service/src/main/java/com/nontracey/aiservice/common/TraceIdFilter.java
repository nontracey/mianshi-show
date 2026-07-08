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

/** traceId 过滤器:每请求生成 traceId 注入 MDC(日志贯穿)+ 响应头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID = "traceId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(TRACE_ID, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            if (resp instanceof HttpServletResponse http) {
                http.setHeader("X-Trace-Id", traceId);
            }
            MDC.remove(TRACE_ID);
        }
    }

    public static String current() {
        return MDC.get(TRACE_ID);
    }
}
