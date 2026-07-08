package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.rag.Splitter.Chunk;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.springframework.stereotype.Service;

import java.util.*;

/** 混合检索:向量 + BM25(纯 Java TF-IDF 式)+ RRF 融合。
 * 与 B 同策略;rerank 暂未接入(留 TODO,可接 bge-reranker HTTP)。 */
@Service
public class HybridRetriever {

    private final VectorStoreService vectorStore;
    private final Bm25Index bm25 = new Bm25Index();

    public HybridRetriever(VectorStoreService vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void rebuildBm25(List<Chunk> chunks) {
        bm25.build(chunks);
    }

    public List<ScoredDoc> retrieve(String query, int topK, String mode) {
        int vecK = Math.max(topK * 2, 8);
        List<ScoredDoc> vec = vectorStore.query(query, vecK);

        if ("vector".equals(mode)) {
            return vec.subList(0, Math.min(topK, vec.size()));
        }

        List<Bm25Index.Hit> bm = bm25.query(query, vecK);
        List<ScoredDoc> fused = rrfFuse(vec, bm, 60);
        return fused.subList(0, Math.min(topK, fused.size()));
    }

    /** RRF 融合:score = Σ 1/(k + rank)。 */
    private List<ScoredDoc> rrfFuse(List<ScoredDoc> vec, List<Bm25Index.Hit> bm, int k) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, ScoredDoc> docs = new HashMap<>();
        for (int i = 0; i < vec.size(); i++) {
            String key = key(vec.get(i).chunk());
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docs.putIfAbsent(key, vec.get(i));
        }
        for (int i = 0; i < bm.size(); i++) {
            String key = key(bm.get(i).chunk());
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docs.putIfAbsent(key, new ScoredDoc(bm.get(i).chunk(), 0.0));
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new ScoredDoc(docs.get(e.getKey()).chunk(), e.getValue()))
                .toList();
    }

    private String key(Chunk c) {
        return c.text().length() > 64 ? c.text().substring(0, 64) : c.text();
    }

    /** 纯 Java BM25(简化为 TF-IDF 式)。 */
    static class Bm25Index {
        private List<Chunk> docs = List.of();
        private List<List<String>> tokenized = List.of();

        void build(List<Chunk> docs) {
            this.docs = docs;
            this.tokenized = docs.stream().map(c -> tokenize(c.text())).toList();
        }

        List<Hit> query(String q, int topK) {
            if (docs.isEmpty()) return List.of();
            List<String> qt = tokenize(q);
            List<Hit> scored = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                double tf = 0;
                for (String t : qt) tf += Collections.frequency(tokenized.get(i), t);
                double s = tokenized.get(i).isEmpty() ? 0 : tf / tokenized.get(i).size();
                if (s > 0) scored.add(new Hit(docs.get(i), s));
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));
            return scored.subList(0, Math.min(topK, scored.size()));
        }

        private List<String> tokenize(String text) {
            List<String> out = new ArrayList<>();
            StringBuilder buf = new StringBuilder();
            for (char ch : text.toCharArray()) {
                if (ch >= '一' && ch <= '鿿') {
                    if (buf.length() > 0) { out.add(buf.toString().toLowerCase()); buf.setLength(0); }
                    out.add(String.valueOf(ch));
                } else if (Character.isLetterOrDigit(ch)) {
                    buf.append(ch);
                } else {
                    if (buf.length() > 0) { out.add(buf.toString().toLowerCase()); buf.setLength(0); }
                }
            }
            if (buf.length() > 0) out.add(buf.toString().toLowerCase());
            return out;
        }

        record Hit(Chunk chunk, double score) {}
    }
}
