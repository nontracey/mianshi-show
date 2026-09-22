package com.mianshi.mcp.cost;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Token/调用成本台账：按工具累计，供评测报告与成本看板使用（ADR-4 配套）。 */
public final class CostLedger {

    public static final class Usage {
        public final AtomicLong promptTokens = new AtomicLong();
        public final AtomicLong completionTokens = new AtomicLong();
        public final AtomicLong calls = new AtomicLong();
        public Map<String, Object> snapshot() {
            return Map.of("calls", calls.get(), "promptTokens", promptTokens.get(), "completionTokens", completionTokens.get());
        }
    }

    private final Map<String, Usage> byTool = new ConcurrentHashMap<>();

    public void record(String tool, long promptTokens, long completionTokens) {
        Usage u = byTool.computeIfAbsent(tool, k -> new Usage());
        u.calls.incrementAndGet();
        u.promptTokens.addAndGet(promptTokens);
        u.completionTokens.addAndGet(completionTokens);
    }

    public Map<String, Usage> byTool() { return Map.copyOf(byTool); }

    public long totalTokens() {
        return byTool.values().stream().mapToLong(u -> u.promptTokens.get() + u.completionTokens.get()).sum();
    }

    public List<Map<String, Object>> report() {
        return byTool.entrySet().stream()
                .map(e -> Map.of("tool", (Object) e.getKey(), "usage", (Object) e.getValue().snapshot()))
                .toList();
    }
}
