package com.nontracey.aiservice.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrieverTest {
    @Test
    void keywordIndexRanksMatchingChunkFirst() {
        var index = new HybridRetriever.Bm25Index();
        var java = new Splitter.Chunk("volatile 保证内存可见性", Map.of("chunk_id", "java"));
        var db = new Splitter.Chunk("MySQL B+ 树索引", Map.of("chunk_id", "db"));
        index.build(List.of(java, db));
        assertThat(index.query("volatile 可见性", 2))
                .extracting(hit -> hit.chunk().metadata().get("chunk_id"))
                .containsExactly("java");
    }
}
