package com.nontracey.aiservice.common;

import com.nontracey.aiservice.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class TenantFilterTest {
    @Test
    void mapsCredentialToTenantAndClearsContext() throws Exception {
        var filter = new TenantFilter(new SecurityProperties(false, Map.of("secret", "tenant-a")));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "secret");
        var seen = new AtomicReference<String>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, resp) -> seen.set(TenantContext.get()));
        assertThat(seen.get()).isEqualTo("tenant-a");
        assertThat(TenantContext.get()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }

    @Test
    void rejectsUntrustedClientTenantHeader() throws Exception {
        var filter = new TenantFilter(new SecurityProperties(false, Map.of()));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "victim");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, resp) -> {});
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void explicitAsyncScopeRestoresPreviousTenant() {
        var seen = new AtomicReference<String>();
        TenantContext.runAs("tenant-worker", () -> seen.set(TenantContext.get()));
        assertThat(seen.get()).isEqualTo("tenant-worker");
        assertThat(TenantContext.get()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }
}
