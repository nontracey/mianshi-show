package com.nontracey.aiservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.rag.Splitter.Chunk;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混合检索器(RAG 的检索环节):向量 + BM25(纯 Java TF-IDF 式)+ RRF 融合 + 可选 LLM 重排。
 *
 * <p><b>架构位置</b>:rag 模块,位于 VectorStoreService 与 Generator 之间。RagController 的
 * hybrid / hybrid_rerank 模式以及 AgentTools 的 search_knowledge 都走这里。
 *
 * <p><b>为什么要混合</b>:向量擅长语义近似,BM25 擅长关键词精确命中;两路召回后用 RRF 融合,
 * 比单路更稳。hybrid_rerank 再用 LLM 对融合结果重排(跨语言一致,无需部署 cross-encoder)。
 *
 * <p><b>三种 mode</b>:
 * <ul>
 *   <li>{@code vector}:只走向量检索。</li>
 *   <li>{@code hybrid}:向量 + BM25,RRF 融合。</li>
 *   <li>{@code hybrid_rerank}:在 hybrid 基础上追加 LLM 重排。</li>
 * </ul>
 *
 * <p><b>租户隔离</b>:BM25 索引按 {@link TenantContext#get()} 分桶存储,互不可见。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/rag/retriever.py}。
 */
@Service
public class HybridRetriever {

    /** 用于解析 LLM 重排输出的 JSON。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VectorStoreService vectorStore;
    private final ChatClient chatClient;
    /** 租户隔离的 BM25 索引:tenantId -> Bm25Index。 */
    private final Map<String, Bm25Index> bm25ByTenant = new ConcurrentHashMap<>();

    public HybridRetriever(VectorStoreService vectorStore, ChatClient chatClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
    }

    /**
     * 为当前租户重建 BM25 索引(ingest 时、chunks 变更后调用)。
     *
     * @param chunks 由 Splitter 产出的全部 chunk
     */
    public void rebuildBm25(List<Chunk> chunks) {
        Bm25Index idx = new Bm25Index();
        idx.build(chunks);
        // 按当前租户覆盖写入,实现索引级租户隔离
        bm25ByTenant.put(TenantContext.get(), idx);
    }

    /**
     * 按 mode 执行检索并返回 topK 结果。
     *
     * @param query 查询文本
     * @param topK  最终返回条数
     * @param mode  vector / hybrid / hybrid_rerank
     * @return 带分数的 chunk 列表(融合/重排后)
     */
    public List<ScoredDoc> retrieve(String query, int topK, String mode) {
        // 过采样:每路先多召回一些(至少 8 条),给融合留足候选,最后再截到 topK
        int vecK = Math.max(topK * 2, 8);
        List<ScoredDoc> vec = vectorStore.query(query, vecK);

        // 纯向量模式:直接截断返回,不走 BM25 / 融合
        if ("vector".equals(mode)) {
            return vec.subList(0, Math.min(topK, vec.size()));
        }

        // BM25 分支:取当前租户的索引(可能为空,例如该租户还没 ingest)
        Bm25Index idx = bm25ByTenant.get(TenantContext.get());
        List<Bm25Index.Hit> bm = idx == null ? List.of() : idx.query(query, vecK);
        // RRF 融合两路结果,k=60 为经验常数
        List<ScoredDoc> fused = rrfFuse(vec, bm, 60);
        if ("hybrid_rerank".equals(mode)) {
            // 取融合结果的前 max(topK*3,10) 条交给 LLM 重排,控制重排成本
            int n = Math.min(fused.size(), Math.max(topK * 3, 10));
            return llmRerank(query, fused.subList(0, n), topK);
        }
        return fused.subList(0, Math.min(topK, fused.size()));
    }

    /**
     * LLM 重排:让模型按与问题的相关度对融合候选重新排序;任何失败都回退原 RRF 顺序。
     *
     * <p>设计要点:只把每条候选的前 200 字喂给 LLM(控制 token),要求只输出
     * {@code {"order":[序号,...]}};解析出的序号映射回候选,未覆盖的候选按原序补在末尾。
     *
     * @param query 原始问题
     * @param cands 待重排候选(已按 RRF 粗排)
     * @param topK  最终返回条数
     * @return 重排后的 topK 结果
     */
    private List<ScoredDoc> llmRerank(String query, List<ScoredDoc> cands, int topK) {
        // 单条无需排序
        if (cands.size() <= 1) return cands.subList(0, Math.min(topK, cands.size()));
        // 拼候选文本,每条截断到 200 字,前缀 [序号] 供模型引用
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
            // 容错:剥掉模型可能包裹的 ```json 代码围栏再解析
            raw = raw.trim().replaceAll("(?s)^```json|^```|```$", "").trim();
            var node = MAPPER.readTree(raw).get("order");
            List<ScoredDoc> reranked = new ArrayList<>();
            if (node != null && node.isArray()) {
                // 按模型给出的序号取候选;越界/非法序号跳过
                for (var n : node) { int i = n.asInt(-1); if (i >= 0 && i < cands.size()) reranked.add(cands.get(i)); }
            }
            // 模型没覆盖到的候选按原 RRF 顺序补在后面,保证不丢结果
            for (ScoredDoc d : cands) if (!reranked.contains(d)) reranked.add(d);
            return reranked.subList(0, Math.min(topK, reranked.size()));
        } catch (Exception e) {
            // LLM/解析失败:回退原 RRF 顺序,保证可用性
            return cands.subList(0, Math.min(topK, cands.size()));
        }
    }

    /**
     * RRF(Reciprocal Rank Fusion)融合两路检索结果:score = Σ 1/(k + rank)。
     *
     * <p><b>为什么用排名(rank)而非原始分</b>:向量余弦分与 BM25 分量纲/分布完全不同,直接加权不可靠;
     * RRF 只看各自排名,天然规避分数不可比问题,且对两路都靠前的文档给出更高分(叠加)。
     *
     * @param vec 向量路召回(已按分降序)
     * @param bm  BM25 路召回(已按分降序)
     * @param k   平滑常数(常用 60),避免头部排名权重过大
     * @return 融合后按 RRF 分降序的结果
     */
    private List<ScoredDoc> rrfFuse(List<ScoredDoc> vec, List<Bm25Index.Hit> bm, int k) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, ScoredDoc> docs = new HashMap<>();
        // 向量路:按排名 i 累加 1/(k + rank),rank 从 1 开始
        for (int i = 0; i < vec.size(); i++) {
            String key = key(vec.get(i).chunk());
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docs.putIfAbsent(key, vec.get(i));
        }
        // BM25 路:同样按排名累加;同一 chunk 两路都命中时分数叠加,排名靠前
        for (int i = 0; i < bm.size(); i++) {
            String key = key(bm.get(i).chunk());
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docs.putIfAbsent(key, new ScoredDoc(bm.get(i).chunk(), 0.0));
        }
        // 按 RRF 分降序输出,分数替换为融合分
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new ScoredDoc(docs.get(e.getKey()).chunk(), e.getValue()))
                .toList();
    }

    /**
     * 融合时的 chunk 去重 key。
     *
     * @param c chunk
     * @return chunk 全文(用全文去重,避免前 N 字相同的 chunk 被误并)
     */
    private String key(Chunk c) {
        return c.text();  // 用全文去重,避免前 N 字相同的 chunk 被误并
    }

    /**
     * 纯 Java BM25(简化为 TF-IDF 式)倒排/打分索引。
     *
     * <p><b>简化说明</b>:不实现完整 BM25 的 IDF/k1/b 参数,而是用"查询词在该文档出现的总次数 / 文档长度"
     * 作为打分(归一化词频),足以支撑与向量路的 RRF 融合(RRF 只看排名不看绝对分)。
     *
     * <p><b>分词策略</b>:中文按单字(unigram)切,英文/数字按连续字母数字聚合成词,均转小写;
     * 不依赖外部分词器,零额外依赖。
     */
    static class Bm25Index {
        /** 参与索引的 chunk 列表。 */
        private List<Chunk> docs = List.of();
        /** 与 docs 一一对应的分词结果。 */
        private List<List<String>> tokenized = List.of();

        /** 构建索引:对全部 chunk 预分词。 */
        void build(List<Chunk> docs) {
            this.docs = docs;
            this.tokenized = docs.stream().map(c -> tokenize(c.text())).toList();
        }

        /**
         * 查询:对每个文档计算归一化词频分,降序取 topK。
         *
         * @param q    查询文本
         * @param topK 返回条数
         * @return 命中的 (chunk, 分) 列表;只包含分 > 0 的文档
         */
        List<Hit> query(String q, int topK) {
            if (docs.isEmpty()) return List.of();
            List<String> qt = tokenize(q);
            List<Hit> scored = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                // 累加查询词在文档中出现的总次数(tf)
                double tf = 0;
                for (String t : qt) tf += Collections.frequency(tokenized.get(i), t);
                // 按文档长度归一化,避免长文档天然占优
                double s = tokenized.get(i).isEmpty() ? 0 : tf / tokenized.get(i).size();
                if (s > 0) scored.add(new Hit(docs.get(i), s));
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));
            return scored.subList(0, Math.min(topK, scored.size()));
        }

        /**
         * 分词:中文按单字切,英文/数字聚合成词,统一小写;其余字符作分隔。
         *
         * @param text 原始文本
         * @return token 列表
         */
        private List<String> tokenize(String text) {
            List<String> out = new ArrayList<>();
            StringBuilder buf = new StringBuilder();
            for (char ch : text.toCharArray()) {
                if (ch >= '一' && ch <= '鿿') {
                    // CJK 统一表意文字区:先把已累积的英文/数字词 flush,再按单字输出
                    if (buf.length() > 0) { out.add(buf.toString().toLowerCase()); buf.setLength(0); }
                    out.add(String.valueOf(ch));
                } else if (Character.isLetterOrDigit(ch)) {
                    // 字母/数字:累积成一个词
                    buf.append(ch);
                } else {
                    // 其他字符作为分隔符
                    if (buf.length() > 0) { out.add(buf.toString().toLowerCase()); buf.setLength(0); }
                }
            }
            if (buf.length() > 0) out.add(buf.toString().toLowerCase());
            return out;
        }

        /** BM25 命中结果(chunk + 归一化词频分)。 */
        record Hit(Chunk chunk, double score) {}
    }
}
