package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorStoreServiceTest {
    @Test
    void memoryOperationsAreTenantScoped() {
        var embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.contains("volatile") ? new float[]{1, 0} : new float[]{0, 1};
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> stores = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(null);
        when(jdbc.getIfAvailable()).thenReturn(null);
        var properties = new AppProperties(
                new AppProperties.Kb("", "", ""), "memory", 20);
        var service = new VectorStoreService(embedding, properties, stores, jdbc);

        TenantContext.runAs("tenant-a", () -> service.addChunks(List.of(
                new Splitter.Chunk("volatile 可见性", Map.of("chunk_id", "a")))));
        TenantContext.runAs("tenant-b", () -> service.addChunks(List.of(
                new Splitter.Chunk("MySQL 索引", Map.of("chunk_id", "b")))));

        var tenantAResult = new AtomicReference<List<VectorStoreService.ScoredDoc>>();
        var tenantBCount = new AtomicInteger();
        TenantContext.runAs("tenant-a", () -> tenantAResult.set(service.query("volatile", 10)));
        TenantContext.runAs("tenant-b", () -> tenantBCount.set(service.count()));

        assertThat(tenantAResult.get()).hasSize(1);
        assertThat(tenantAResult.get().get(0).chunk().metadata().get("chunk_id")).isEqualTo("a");
        assertThat(tenantBCount.get()).isEqualTo(1);

        TenantContext.runAs("tenant-a", service::reset);
        var counts = new int[2];
        TenantContext.runAs("tenant-a", () -> counts[0] = service.count());
        TenantContext.runAs("tenant-b", () -> counts[1] = service.count());
        assertThat(counts).containsExactly(0, 1);
    }
}
