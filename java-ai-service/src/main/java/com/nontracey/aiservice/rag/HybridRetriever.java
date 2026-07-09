package com.nontracey.aiservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.rag.Splitter.Chunk;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 混合检索:向量 + BM25(纯 Java TF-IDF 式)+ RRF 融合 + 可选 LLM 重排。
 * 与 B/D 同策略;hybrid_rerank 用 LLM 对融合结果重排(跨语言一致,无需部署 cross-encoder)。
 * <p>按租户隔离:BM25 索引按 {@link TenantContext#get()} 分桶存储。 */
@Service
public class HybridRetriever {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VectorStoreService vectorStore;
    private final ChatClient chatClient;
    private final Map<String, Bm25Index> bm25ByTenant = new ConcurrentHashMap<>();

    public HybridRetriever(VectorStoreService vectorStore, ChatClient chatClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
    }

    public void rebuildBm25(List<Chunk> chunks) {
        Bm25Index idx = new Bm25Index();
        idx.build(chunks);
        bm25ByTenant.put(TenantContext.get(), idx);
    }

    public List<ScoredDoc> retrieve(String query, int topK, String mode) {
        int vecK = Math.max(topK * 2, 8);
        List<ScoredDoc> vec = vectorStore.query(query, vecK);

        if ("vector".equals(mode)) {
            return vec.subList(0, Math.min(topK, vec.size()));
        }

        Bm25Index idx = bm25ByTenant.get(TenantContext.get());
        List<Bm25Index.Hit> bm = idx == null ? List.of() : idx.query(query, vecK);
        List<ScoredDoc> fused = rrfFuse(vec, bm, 60);
        if ("hybrid_rerank".equals(mode)) {
            int n = Math.min(fused.size(), Math.max(topK * 3, 10));
            return llmRerank(query, fused.subList(0, n), topK);
        }
        return fused.subList(0, Math.min(topK, fused.size()));
    }

    /** LLM 重排:按与问题相关度重排融合结果;失败回退原 RRF 顺序。 */
    private List<ScoredDoc> llmRerank(String query, List<ScoredDoc> cands, int topK) {
        if (cands.size() <= 1) return cands.subList(0, Math.min(topK, cands.size()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cands.size(); i++) {
            String t = cands.get(i).chunk().text();
            sb.append("[").append(i).append("] ").append(t.length() > 200 ? t.substring(0, 200) : t).append("\n");
        }
        try {
            String raw = chatClient.prompt()
                    .system("你是检索结果重排器。按候选与【问题】的相关度从高到低排序,只输出 JSON:{\"order\":[序号,...]}。不要解释。")
                    .user("问题:" + query + "\n候选:\n" + sb)
                    .call().content();
            if (raw == null) return cands.subList(0, Math.min(topK, cands.size()));
            raw = raw.trim().replaceAll("(?s)^```json|^```|```$", "").trim();
            var node = MAPPER.readTree(raw).get("order");
            List<ScoredDoc> reranked = new ArrayList<>();
            if (node != null && node.isArray()) {
                for (var n : node) { int i = n.asInt(-1); if (i >= 0 && i < cands.size()) reranked.add(cands.get(i)); }
            }
            for (ScoredDoc d : cands) if (!reranked.contains(d)) reranked.add(d);
            return reranked.subList(0, Math.min(topK, reranked.size()));
        } catch (Exception e) {
            return cands.subList(0, Math.min(topK, cands.size()));
        }
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
        return c.text();  // 用全文去重,避免前 N 字相同的 chunk 被误并
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
