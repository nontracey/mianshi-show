package com.nontracey.aiservice.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 多租户过滤器:从请求头 {@code X-Tenant-Id} 解析租户,写入 {@link TenantContext} 与 MDC。
 *
 * <p><b>架构位置</b>:common 层多租户入口。下游的知识库(Loader)、向量库(VectorStoreService)、
 * BM25 索引(HybridRetriever)都按 {@link TenantContext#get()} 做数据隔离,从而实现"同一进程、
 * 多租户互不可见"的效果。
 *
 * <p><b>执行顺序</b>:用 {@code @Order} 控制。{@link TraceIdFilter} 是 HIGHEST_PRECEDENCE(最先),
 * 本过滤器是 HIGHEST_PRECEDENCE + 1(紧随其后),保证 traceId 已就绪、且租户在业务逻辑之前就位。
 *
 * <p><b>为什么在 finally 里清理</b>:Servlet 容器会复用线程,若不清理 ThreadLocal / MDC,
 * 下一个请求可能读到上一个请求的租户/traceId,造成串号。
 *
 * <p><b>同构映射</b>:对应 B 项目 FastAPI 的租户解析中间件。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantFilter implements Filter {

    /** 租户标识请求头名称。 */
    public static final String TENANT_HEADER = "X-Tenant-Id";
    /** 写入 MDC 的 key,供日志 pattern 打印租户。 */
    private static final String MDC_KEY = "tenantId";

    /**
     * 解析租户 -> 写入上下文与 MDC -> 放行 -> 清理。
     *
     * @param req   请求(从中读取 X-Tenant-Id 头)
     * @param resp  响应
     * @param chain 过滤器链
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // 仅 HTTP 请求才有 header;读取租户,缺省回退 default
        String tenant = null;
        if (req instanceof HttpServletRequest http) {
            tenant = http.getHeader(TENANT_HEADER);
        }
        if (tenant == null || tenant.isBlank()) tenant = TenantContext.DEFAULT_TENANT;
        // 写入 ThreadLocal(业务用)与 MDC(日志用)
        TenantContext.set(tenant);
        MDC.put(MDC_KEY, tenant);
        try {
            chain.doFilter(req, resp);
        } finally {
            // 线程会被容器复用,必须清理,避免租户/日志串号
            MDC.remove(MDC_KEY);
            TenantContext.clear();
        }
    }
}
