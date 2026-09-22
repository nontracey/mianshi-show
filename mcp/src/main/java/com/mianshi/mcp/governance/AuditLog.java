package com.mianshi.mcp.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 结构化审计事件：可回放，不静默（ADR-2/ADR-3）。 */
public final class AuditLog {

    public record AuditEvent(Instant at, String tool, String event, String outcome,
                             long durationMs, Map<String, Object> detail) {
        public String toJsonLine() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"at\":\"").append(at).append("\"");
            sb.append(",\"tool\":\"").append(tool).append("\"");
            sb.append(",\"event\":\"").append(event).append("\"");
            sb.append(",\"outcome\":\"").append(outcome).append("\"");
            sb.append(",\"durationMs\":").append(durationMs);
            if (detail != null && !detail.isEmpty()) {
                sb.append(",\"detail\":{");
                boolean first = true;
                for (var e : detail.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(e.getKey()).append("\":\"")
                      .append(String.valueOf(e.getValue()).replace("\"", "'")).append("\"");
                }
                sb.append("}");
            }
            return sb.append("}").toString();
        }
    }

    private final ConcurrentLinkedDeque<AuditEvent> events = new ConcurrentLinkedDeque<>();

    public void record(String tool, String event, String outcome, long durationMs, Map<String, Object> detail) {
        events.add(new AuditEvent(Instant.now(), tool, event, outcome, durationMs, detail));
    }

    public List<AuditEvent> snapshot() {
        return new ArrayList<>(events);
    }

    public List<AuditEvent> byTool(String tool) {
        return events.stream().filter(e -> e.tool().equals(tool)).toList();
    }

    /** 安全事件检查：评测的安全分依赖这个（ADR-4）。 */
    public boolean hasUnsafeEvent(String tool) {
        return events.stream().anyMatch(e -> e.tool().equals(tool) && "unsafe".equals(e.outcome()));
    }
}
