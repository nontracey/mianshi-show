package com.mianshi.mcp.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 内存关键词检索（默认实现）：CJK 单字 + ASCII 词元计数打分。
 * 已知短板：无 IDF、无语义召回——语义通道由 pgvector profile 提供（ADR-4 对照组语义通道留位）。
 */
@Component
public class InMemoryKeywordSearch implements SearchService {

    public record Doc(String id, String title, String content) {}

    private final List<Doc> docs = new ArrayList<>();

    public InMemoryKeywordSearch() {
        try {
            PathMatchingResourcePatternResolver r = new PathMatchingResourcePatternResolver();
            for (Resource res : r.getResources("classpath:knowledge/*.md")) {
                try (var in = res.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String name = res.getFilename() != null ? res.getFilename().replaceFirst("\\.md$", "") : "doc";
                    docs.add(new Doc(name, name, content));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载知识语料失败", e);
        }
    }

    static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c > 0x4E00 && c < 0x9FFF) {
                if (ascii.length() > 0) { out.add(ascii.toString().toLowerCase()); ascii.setLength(0); }
                out.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                ascii.append(Character.toLowerCase(c));
            } else if (ascii.length() > 0) {
                out.add(ascii.toString().toLowerCase()); ascii.setLength(0);
            }
        }
        if (ascii.length() > 0) out.add(ascii.toString().toLowerCase());
        return out;
    }

    @Override
    public List<SearchHit> search(String query, int topK) {
        List<String> terms = terms(query);
        Set<String> uniq = new HashSet<>(terms);
        List<SearchHit> hits = new ArrayList<>();
        for (Doc d : docs) {
            double score = 0;
            for (String t : uniq) {
                int idx = 0, count = 0;
                while ((idx = d.content().indexOf(t, idx)) >= 0) { count++; idx += t.length(); }
                score += count;
            }
            if (score > 0) {
                String snippet = d.content().length() > 220 ? d.content().substring(0, 220) + "…" : d.content();
                hits.add(new SearchHit(d.id(), d.title(), snippet, score, "inmemory:" + d.id()));
            }
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.subList(0, Math.min(topK, hits.size()));
    }
}
