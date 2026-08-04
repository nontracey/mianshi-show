package com.nontracey.aiservice.infra;

import com.nontracey.aiservice.dto.Dtos.AskData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义缓存:question 向量与历史问题向量的 cosine 相似度超过阈值即视为命中,直接复用历史答案,
 * 省一次 LLM 调用。与 B/D 同策略(阈值 0.95),内存实现(dev);生产可换 Redis + 向量近邻。
 *
 * <p><b>架构位置</b>:infra 层。RagController 在 /api/ask 中先对问题向量化,查本缓存,
 * 命中则直接返回(并在 usage 标注 cache_hit=true),未命中才走检索 + 生成,最后回写缓存。
 *
 * <p><b>相似度判定</b>:阈值 0.95 很严格,只有"几乎同一问题的不同措辞"才会命中,
 * 避免把语义相近但意图不同的问题误判为同一问(宁可不命中,不可答非所问)。
 *
 * <p><b>实现取舍</b>:线性扫描 + synchronized,实现简单、正确性优先;条目少(dev)时性能足够,
 * 规模上来后应替换为 ANN 近邻索引。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/infra/cache.py}。
 */
@Component
public class SemanticCache {

    /** 命中判定阈值:cosine >= 0.95 视为同一问题。 */
    private static final double THRESHOLD = 0.95;
    /** 历史(问题向量, 答案)条目列表;synchronized 方法保证并发读写安全。 */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * 查询缓存:线性遍历,返回第一条相似度达标的历史答案。
     *
     * @param qEmb 当前问题的 embedding
     * @return 命中时返回历史 AskData;未命中返回 null
     */
    public synchronized AskData get(float[] qEmb) {
        for (Entry e : entries) {
            if (cosine(qEmb, e.emb) >= THRESHOLD) return e.data;
        }
        return null;
    }

    /**
     * 写入缓存:记录(问题向量, 答案)。
     *
     * @param qEmb 问题 embedding
     * @param data 对应的 RAG 答案
     */
    public synchronized void put(float[] qEmb, AskData data) {
        entries.add(new Entry(qEmb, data));
    }

    /** 计算两个向量的余弦相似度;维度不一致或零向量时返回 0。 */
    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 缓存条目:问题 embedding + 对应答案。 */
    private record Entry(float[] emb, AskData data) {}
}
