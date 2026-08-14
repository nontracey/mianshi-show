package com.nontracey.aiservice.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class PgVectorStoreIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void persistsSearchesFiltersAndDeletesTenantDocuments() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.dimensions()).thenReturn(3);
        when(embeddings.embed(anyList(), any(), any())).thenAnswer(inv -> {
            List<Document> documents = inv.getArgument(0);
            return documents.stream().map(doc -> doc.getText().contains("volatile")
                    ? new float[]{1, 0, 0} : new float[]{0, 1, 0}).toList();
        });
        when(embeddings.embed(any(String.class))).thenReturn(new float[]{1, 0, 0});
        var store = PgVectorStore.builder(new JdbcTemplate(dataSource), embeddings)
                .dimensions(3).initializeSchema(true).build();
        var aId = UUID.randomUUID().toString();
        var bId = UUID.randomUUID().toString();
        var a = new Document(aId, "volatile 可见性", Map.of("tenant_id", "a"));
        var b = new Document(bId, "MySQL 索引", Map.of("tenant_id", "b"));
        store.add(List.of(a, b));
        var result = store.similaritySearch(SearchRequest.builder().query("volatile")
                .topK(10).filterExpression("tenant_id == 'a'").build());
        assertThat(result).extracting(Document::getId).containsExactly(aId);
        store.delete(List.of(aId));
        assertThat(store.similaritySearch(SearchRequest.builder().query("volatile")
                .topK(10).filterExpression("tenant_id == 'a'").build())).isEmpty();
    }
}
