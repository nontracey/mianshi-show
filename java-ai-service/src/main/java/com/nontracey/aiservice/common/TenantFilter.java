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

/** 租户过滤器:从 X-Tenant-Id 头读租户(缺省 default),存 TenantContext + MDC。
 * <p>KB / 向量库 / BM25 都按租户隔离(见 Loader / VectorStoreService / HybridRetriever)。
 * <p>在 TraceIdFilter 之后执行(TRACE_ID 是 HIGHEST_PRECEDENCE,这里 +1)。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantFilter implements Filter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String MDC_KEY = "tenantId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        String tenant = null;
        if (req instanceof HttpServletRequest http) {
            tenant = http.getHeader(TENANT_HEADER);
        }
        if (tenant == null || tenant.isBlank()) tenant = TenantContext.DEFAULT_TENANT;
        TenantContext.set(tenant);
        MDC.put(MDC_KEY, tenant);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(MDC_KEY);
            TenantContext.clear();
        }
    }
}
