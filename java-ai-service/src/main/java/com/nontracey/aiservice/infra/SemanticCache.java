package com.nontracey.aiservice.infra;

import com.nontracey.aiservice.dto.Dtos.AskData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 语义缓存:question 向量与历史向量 cosine 相似度 > 阈值即命中,省一次 LLM 调用。
 * 与 B/D 同策略(阈值 0.95),内存实现(dev);生产可换 Redis + 向量近邻。 */
@Component
public class SemanticCache {

    private static final double THRESHOLD = 0.95;
    private final List<Entry> entries = new ArrayList<>();

    public synchronized AskData get(float[] qEmb) {
        for (Entry e : entries) {
            if (cosine(qEmb, e.emb) >= THRESHOLD) return e.data;
        }
        return null;
    }

    public synchronized void put(float[] qEmb, AskData data) {
        entries.add(new Entry(qEmb, data));
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Entry(float[] emb, AskData data) {}
}
