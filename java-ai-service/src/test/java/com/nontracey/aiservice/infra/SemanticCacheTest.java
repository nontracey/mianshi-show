package com.nontracey.aiservice.infra;

import com.nontracey.aiservice.dto.Dtos;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheTest {
    @Test
    void rejectsSemanticallyDifferentVector() {
        var cache = new SemanticCache();
        var answer = new Dtos.AskData("a", List.of(), Map.of());
        cache.put(new float[]{1, 0}, answer);
        assertThat(cache.get(new float[]{1, 0})).isSameAs(answer);
        assertThat(cache.get(new float[]{0, 1})).isNull();
    }
}
