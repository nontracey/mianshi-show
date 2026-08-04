package com.nontracey.aiservice.infra;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内指标聚合(轻量版可观测性,与 B 的 Metrics 等价)。
 *
 * <p><b>架构位置</b>:infra 层。各 Controller 在请求结束时 recordRequest(耗时),
 * RagController 记录语义缓存命中,SemanticCache/Generator 侧记录 LLM 调用;
 * OpsController 的 /api/metrics 通过 {@link #snapshot()} 输出聚合结果。
 *
 * <p><b>线程安全</b>:全部计数器用 {@link AtomicInteger}/{@link AtomicLong},支持并发请求下无锁累加。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/infra/observability.py}。
 */
@Component
public class Metrics {

    /** 累计请求数。 */
    private final AtomicInteger requestsTotal = new AtomicInteger();
    /** 累计 token 消耗(由各 LLM 调用点上报)。 */
    private final AtomicInteger tokensTotal = new AtomicInteger();
    /** LLM 调用次数。 */
    private final AtomicInteger llmCalls = new AtomicInteger();
    /** 语义缓存命中次数。 */
    private final AtomicInteger cacheHits = new AtomicInteger();
    /** 语义缓存未命中次数。 */
    private final AtomicInteger cacheMisses = new AtomicInteger();
    /** 请求耗时总和(毫秒),配合 latencyCount 求平均。 */
    private final AtomicLong latencySum = new AtomicLong();
    /** 参与平均耗时统计的请求数。 */
    private final AtomicInteger latencyCount = new AtomicInteger();

    /**
     * 记录一次请求及其耗时。
     *
     * @param latencyMs 本次请求耗时(毫秒)
     */
    public void recordRequest(long latencyMs) {
        requestsTotal.incrementAndGet();
        latencySum.addAndGet(latencyMs);
        latencyCount.incrementAndGet();
    }

    /**
     * 记录一次 LLM 调用及消耗的 token。
     *
     * @param tokens 本次调用消耗的 token 数
     */
    public void recordLlm(int tokens) {
        llmCalls.incrementAndGet();
        tokensTotal.addAndGet(tokens);
    }

    /**
     * 记录一次语义缓存查询结果。
     *
     * @param hit 是否命中
     */
    public void recordCache(boolean hit) {
        (hit ? cacheHits : cacheMisses).incrementAndGet();
    }

    /**
     * 输出当前指标快照(供 /api/metrics)。
     *
     * <p>命中率 = hits/(hits+misses);平均延迟 = 总耗时/请求数;均做四舍五入保留 4/2 位,
     * 无数据时返回 0 避免除零。
     *
     * @return 指标快照 DTO
     */
    public com.nontracey.aiservice.dto.Dtos.MetricsData snapshot() {
        int hits = cacheHits.get(), misses = cacheMisses.get();
        int total = hits + misses;
        // 命中率保留 4 位小数;无缓存查询时为 0
        double hitRate = total == 0 ? 0 : Math.round(hits * 10000.0 / total) / 10000.0;
        int lc = latencyCount.get();
        // 平均耗时保留 2 位小数;无请求时为 0
        double avg = lc == 0 ? 0 : Math.round(latencySum.get() * 100.0 / lc) / 100.0;
        return new com.nontracey.aiservice.dto.Dtos.MetricsData(
                requestsTotal.get(), tokensTotal.get(), hits, misses, hitRate, avg, llmCalls.get()
        );
    }
}
