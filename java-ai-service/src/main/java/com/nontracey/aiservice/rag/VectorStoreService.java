package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.rag.Splitter.Chunk;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 内存向量库 + Embedding 封装。
 * add:chunk -> embed -> 存;query:embedding -> cosine 排序。
 * 纯 Java 实现,零外部依赖(与 B 的 InMemoryVectorStore 等价)。 */
@Service
public class VectorStoreService {

    private final EmbeddingModel embeddingModel;
    private final List<Entry> store = new ArrayList<>();

    public VectorStoreService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public synchronized void add(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        // Spring AI embed:单条调用(API 稳定,避免批量 MetadataMode 版本差异)
        for (Chunk c : chunks) {
            float[] emb = embeddingModel.embed(c.text());
            store.add(new Entry(c, toDouble(emb)));
        }
    }

    public List<ScoredDoc> query(String question, int topK) {
        if (store.isEmpty()) return List.of();
        float[] q = embeddingModel.embed(question);
        List<ScoredDoc> scored = new ArrayList<>();
        for (Entry e : store) {
            scored.add(new ScoredDoc(e.chunk, cosine(q, e.embedding)));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    public synchronized void reset() { store.clear(); }
    public int count() { return store.size(); }

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

    private record Entry(Chunk chunk, double[] embedding) {}
    public record ScoredDoc(Chunk chunk, double score) {}
}
