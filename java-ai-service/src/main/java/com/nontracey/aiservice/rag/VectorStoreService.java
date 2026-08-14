package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.config.AppProperties;
import com.nontracey.aiservice.rag.Splitter.Chunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 内存向量库 + Embedding 封装(RAG 的存储/检索环节)。
 *
 * <p><b>架构位置</b>:rag 模块核心。同时面向两类调用方:
 * <ul>
 *   <li>实现 Spring AI 的 {@link VectorStore} 接口,供 {@code QuestionAnswerAdvisor} 走标准
 *       {@link #similaritySearch} 检索路径(Generator 的 advisor 模式)。</li>
 *   <li>保留 Chunk-based API({@link #addChunks}/{@link #query}/{@link #reset}/{@link #count}),
 *       供 {@link HybridRetriever} 做向量 + BM25 + RRF 混合检索。</li>
 * </ul>
 *
 * <p><b>租户隔离</b>:入库(add / addChunks)时给每条 metadata 打上 {@code tenant_id};
 * query / similaritySearch / count / reset 都按 {@link TenantContext#get()} 过滤,只可见当前租户数据。
 *
 * <p><b>存储</b>:进程内 {@link ArrayList},dev 用;每次检索对全量条目算余弦(暴力扫描),
 * 数据量小可接受。生产可切 pgvector:设 {@code app.vector-store=pgvector} 并按 application.yml
 * 注释配置 PG 连接/dimensions,Spring AI starter 会注册 PgVectorStore Bean 替代本内存实现。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/rag/store.py}。
 */
@Service
public class VectorStoreService {

    /** metadata 中标识租户的 key。 */
    public static final String TENANT_META_KEY = "tenant_id";

    /** 由 Spring AI OpenAI starter 自动装配的向量化模型。 */
    private final EmbeddingModel embeddingModel;
    private final AppProperties properties;
    private final ObjectProvider<VectorStore> vectorStores;
    private final ObjectProvider<JdbcTemplate> jdbcTemplates;
    /** 内存存储:chunk + 对应 SpringAI Document + 预计算的 embedding。 */
    private final List<Entry> store = new ArrayList<>();

    public VectorStoreService(EmbeddingModel embeddingModel, AppProperties properties,
                              ObjectProvider<VectorStore> vectorStores,
                              ObjectProvider<JdbcTemplate> jdbcTemplates) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.vectorStores = vectorStores;
        this.jdbcTemplates = jdbcTemplates;
    }

    private boolean pgvector() {
        return "pgvector".equalsIgnoreCase(properties.vectorStore());
    }

    private VectorStore backend() {
        VectorStore store = vectorStores.getIfAvailable();
        if (store == null) throw new IllegalStateException("pgvector 模式未装配 VectorStore");
        return store;
    }

    // ---------- Spring AI VectorStore 接口(供 QuestionAnswerAdvisor 用) ----------

    /**
     * Spring AI 标准入库接口:把若干 {@link Document} 向量化后存入内存。
     *
     * <p>每条都会补上当前租户的 {@code tenant_id} metadata,实现写入即隔离。
     *
     * @param documents 待入库文档
     */
    public void add(List<Document> documents) {
        if (pgvector()) {
            String tenant = TenantContext.get();
            backend().add(documents.stream().map(d -> {
                Map<String, Object> meta = new HashMap<>(d.getMetadata());
                meta.put(TENANT_META_KEY, tenant);
                return new Document(d.getId(), d.getText(), meta);
            }).toList());
            return;
        }
        synchronized (this) {
            String tenant = TenantContext.get();
            for (Document d : documents) {
                // 复制 metadata 并打上租户标记,避免污染调用方原始对象
                Map<String, Object> meta = new HashMap<>(d.getMetadata());
                meta.put(TENANT_META_KEY, tenant);
                float[] emb = embeddingModel.embed(d.getText());
                store.add(new Entry(new Chunk(d.getText(), meta), d, toDouble(emb)));
            }
        }
    }

    /**
     * Spring AI 标准删除接口:按 document id 移除。
     *
     * @param idList 待删除的 document id 列表
     * @return 是否有实际删除发生
     */
    public void delete(List<String> idList) {
        if (pgvector()) {
            backend().delete(idList);
            return;
        }
        synchronized (this) {
            store.removeIf(e -> idList.contains(e.document.getId()));
        }
    }

    /**
     * Spring AI 标准相似度检索接口,{@code QuestionAnswerAdvisor} 走这里。
     *
     * <p>纯向量检索:query 向量化后与当前租户的全部条目算余弦,按阈值过滤、降序取 topK。
     *
     * @param request 检索请求(query / topK / similarityThreshold)
     * @return 命中的 Document 列表(仅当前租户)
     */
    public List<Document> similaritySearch(SearchRequest request) {
        if (pgvector()) {
            String tenant = TenantContext.get().replace("'", "''");
            SearchRequest scoped = SearchRequest.builder()
                    .query(request.getQuery()).topK(request.getTopK())
                    .similarityThreshold(request.getSimilarityThreshold())
                    .filterExpression(TENANT_META_KEY + " == '" + tenant + "'").build();
            return backend().similaritySearch(scoped);
        }
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
        // 按相似度降序,取前 topK
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(topK).map(x -> x.entry.document).toList();
    }

    // ---------- Chunk-based API(供 HybridRetriever/RagController 用) ----------

    /** 批量入库 chunks(ingest 走这里):embed + 存,metadata 透传给 SpringAI Document + 加 tenant_id。
     *
     * @param chunks 由 Splitter 产出的 chunk 列表
     */
    public synchronized void addChunks(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        String tenant = TenantContext.get();
        if (pgvector()) {
            List<Document> docs = chunks.stream().map(c -> {
                Map<String, Object> meta = new HashMap<>(c.metadata());
                meta.put(TENANT_META_KEY, tenant);
                String id = String.valueOf(meta.getOrDefault("chunk_id", UUID.randomUUID().toString()));
                return new Document(id, c.text(), meta);
            }).toList();
            backend().add(docs);
            return;
        }
        for (Chunk c : chunks) {
            // 复制 metadata 并打上租户标记,供后续按租户过滤
            Map<String, Object> meta = new HashMap<>(c.metadata());
            meta.put(TENANT_META_KEY, tenant);
            Chunk tagged = new Chunk(c.text(), meta);
            float[] emb = embeddingModel.embed(c.text());
            // 同时构造 SpringAI Document(带随机 id),使本条也能被标准 similaritySearch 检索
            Document doc = new Document(UUID.randomUUID().toString(), c.text(), meta);
            store.add(new Entry(tagged, doc, toDouble(emb)));
        }
    }

    /**
     * 向量检索(供 HybridRetriever 的向量分支):返回与问题最相似的 topK 个 chunk 及余弦分。
     *
     * @param question 查询文本
     * @param topK     返回条数
     * @return 带分数的 chunk 列表(仅当前租户,按分数降序)
     */
    public List<ScoredDoc> query(String question, int topK) {
        if (pgvector()) {
            SearchRequest request = SearchRequest.builder().query(question).topK(topK)
                    .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL).build();
            return similaritySearch(request).stream().map(d -> new ScoredDoc(
                    new Chunk(d.getText(), d.getMetadata()), d.getScore() == null ? 0.0 : d.getScore()
            )).toList();
        }
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

    /** 清空当前租户的全部向量(ingest 重建前调用),不影响其他租户。 */
    public synchronized void reset() {
        String tenant = TenantContext.get();
        if (pgvector()) {
            JdbcTemplate jdbc = jdbcTemplates.getIfAvailable();
            if (jdbc == null) throw new IllegalStateException("pgvector 模式未装配 JdbcTemplate");
            jdbc.update("DELETE FROM vector_store WHERE metadata ->> ? = ?", TENANT_META_KEY, tenant);
            return;
        }
        store.removeIf(e -> tenant.equals(e.chunk.metadata().get(TENANT_META_KEY)));
    }

    /** 当前租户的向量条数(用于判断向量库是否为空)。 */
    public int count() {
        String tenant = TenantContext.get();
        if (pgvector()) {
            JdbcTemplate jdbc = jdbcTemplates.getIfAvailable();
            if (jdbc == null) return 0;
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM vector_store WHERE metadata ->> ? = ?",
                    Integer.class, TENANT_META_KEY, tenant);
            return count == null ? 0 : count;
        }
        return (int) store.stream()
                .filter(e -> tenant.equals(e.chunk.metadata().get(TENANT_META_KEY)))
                .count();
    }

    /** 对外暴露单条 embedding(供语义缓存对问题向量化)。 */
    public float[] embed(String text) { return embeddingModel.embed(text); }

    /** 计算 float 查询向量与 double 存储向量的余弦相似度;任一向量为零向量时返回 0。 */
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

    /** float[] 转 double[],便于统一存储与余弦计算。 */
    private static double[] toDouble(float[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
    }

    /** 内存中的一条记录:chunk + 对应 SpringAI Document + 预计算 embedding。 */
    private record Entry(Chunk chunk, Document document, double[] embedding) {}
    /** similaritySearch 的中间打分结构。 */
    private record ScoredEntry(Entry entry, double score) {}
    /** 对外的带分数检索结果(chunk + 相关度分数)。 */
    public record ScoredDoc(Chunk chunk, double score) {}
}
