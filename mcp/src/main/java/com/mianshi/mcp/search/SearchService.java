package com.mianshi.mcp.search;

import java.util.List;

/** 检索统一接口：实现可为内存关键词（默认）或 pgvector（pgvector profile）。 */
public interface SearchService {

    record SearchHit(String id, String title, String snippet, double score, String source) {}

    List<SearchHit> search(String query, int topK);
}
