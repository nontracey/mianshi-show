package com.nontracey.aiservice.infra;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 进程内指标聚合(与 B 的 Metrics 等价)。 */
@Component
public class Metrics {

    private final AtomicInteger requestsTotal = new AtomicInteger();
    private final AtomicInteger tokensTotal = new AtomicInteger();
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger cacheMisses = new AtomicInteger();
    private final AtomicLong latencySum = new AtomicLong();
    private final AtomicInteger latencyCount = new AtomicInteger();

    public void recordRequest(long latencyMs) {
        requestsTotal.incrementAndGet();
        latencySum.addAndGet(latencyMs);
        latencyCount.incrementAndGet();
    }

    public void recordLlm(int tokens) {
        llmCalls.incrementAndGet();
        tokensTotal.addAndGet(tokens);
    }

    public void recordCache(boolean hit) {
        (hit ? cacheHits : cacheMisses).incrementAndGet();
    }

    public com.nontracey.aiservice.dto.Dtos.MetricsData snapshot() {
        int hits = cacheHits.get(), misses = cacheMisses.get();
        int total = hits + misses;
        double hitRate = total == 0 ? 0 : Math.round(hits * 10000.0 / total) / 10000.0;
        int lc = latencyCount.get();
        double avg = lc == 0 ? 0 : Math.round(latencySum.get() * 100.0 / lc) / 100.0;
        return new com.nontracey.aiservice.dto.Dtos.MetricsData(
                requestsTotal.get(), tokensTotal.get(), hits, misses, hitRate, avg, llmCalls.get()
        );
    }
}
