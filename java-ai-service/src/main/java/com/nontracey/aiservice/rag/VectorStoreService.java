package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.rag.Splitter.Chunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 内存向量库 + Embedding 封装。
 * <p>实现 Spring AI 的 {@link VectorStore} 接口,供 {@code QuestionAnswerAdvisor} 走标准检索路径;
 * 同时保留 Chunk-based API(供 HybridRetriever 用,带 BM25+RRF 混合检索)。
 * <p>按租户隔离:addChunks 时 metadata 加 {@code tenant_id},query/similaritySearch/count/reset
 * 都按 {@link TenantContext#get()} 过滤当前租户的数据。
 * <p>生产可切 pgvector(见 PgVectorConfig)。 */
@Service
public class VectorStoreService implements VectorStore {

    public static final String TENANT_META_KEY = "tenant_id";

    private final EmbeddingModel embeddingModel;
    private final List<Entry> store = new ArrayList<>();

    public VectorStoreService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    // ---------- Spring AI VectorStore 接口(供 QuestionAnswerAdvisor 用) ----------

    @Override
    public void add(List<Document> documents) {
        synchronized (this) {
            String tenant = TenantContext.get();
            for (Document d : documents) {
                Map<String, Object> meta = new HashMap<>(d.getMetadata());
                meta.put(TENANT_META_KEY, tenant);
                float[] emb = embeddingModel.embed(d.getContent());
                store.add(new Entry(new Chunk(d.getContent(), meta), d, toDouble(emb)));
            }
        }
    }

    @Override
    public Optional<Boolean> delete(List<String> idList) {
        synchronized (this) {
            int before = store.size();
            store.removeIf(e -> idList.contains(e.document.getId()));
            return Optional.of(store.size() < before);
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (store.isEmpty()) return List.of();
        String tenant = TenantContext.get();
        float[] q = embeddingModel.embed(request.getQuery());
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();
        List<ScoredEntry> scored = new ArrayList<>();
        for (Entry e : store) {
            if (!tenant.equals(e.chunk.metadata().get(TENANT_META_KEY))) continue;  // 租户隔离
            double sim = cosine(q, e.embedding);
            if (sim >= threshold) scored.add(new ScoredEntry(e, sim));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(topK).map(x -> x.entry.document).toList();
    }

    // ---------- Chunk-based API(供 HybridRetriever/RagController 用) ----------

    /** 批量入库 chunks(ingest 走这里):embed + 存,metadata 透传给 SpringAI Document + 加 tenant_id。 */
    public synchronized void addChunks(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        String tenant = TenantContext.get();
        for (Chunk c : chunks) {
            Map<String, Object> meta = new HashMap<>(c.metadata());
            meta.put(TENANT_META_KEY, tenant);
            Chunk tagged = new Chunk(c.text(), meta);
            float[] emb = embeddingModel.embed(c.text());
            Document doc = new Document(UUID.randomUUID().toString(), c.text(), meta);
            store.add(new Entry(tagged, doc, toDouble(emb)));
        }
    }

    public List<ScoredDoc> query(String question, int topK) {
        if (store.isEmpty()) return List.of();
        String tenant = TenantContext.get();
        float[] q = embeddingModel.embed(question);
        List<ScoredDoc> scored = new ArrayList<>();
        for (Entry e : store) {
            if (!tenant.equals(e.chunk.metadata().get(TENANT_META_KEY))) continue;  // 租户隔离
            scored.add(new ScoredDoc(e.chunk, cosine(q, e.embedding)));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    public synchronized void reset() {
        String tenant = TenantContext.get();
        store.removeIf(e -> tenant.equals(e.chunk.metadata().get(TENANT_META_KEY)));
    }

    public int count() {
        String tenant = TenantContext.get();
        return (int) store.stream()
                .filter(e -> tenant.equals(e.chunk.metadata().get(TENANT_META_KEY)))
                .count();
    }

    /** 对外暴露单条 embedding(供语义缓存对问题向量化)。 */
    public float[] embed(String text) { return embeddingModel.embed(text); }

    private static double cosine(float[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double[] toDouble(float[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
    }

    private record Entry(Chunk chunk, Document document, double[] embedding) {}
    private record ScoredEntry(Entry entry, double score) {}
    public record ScoredDoc(Chunk chunk, double score) {}
}
