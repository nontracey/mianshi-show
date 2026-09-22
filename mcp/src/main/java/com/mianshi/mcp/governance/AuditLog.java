package com.mianshi.mcp.governance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 结构化审计事件：可回放，不静默（ADR-2/ADR-3）。JSONL 追加式落盘（配置了路径时），重启不丢。 */
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
    private final Path sinkPath;

    public AuditLog() { this.sinkPath = null; }

    /** 带落盘：每条事件追加写入 JSONL；落盘失败不吞——审计自身失败也要留痕（抛出给治理层）。 */
    public AuditLog(Path sinkPath) {
        this.sinkPath = sinkPath;
        if (sinkPath != null) {
            try {
                if (sinkPath.getParent() != null) Files.createDirectories(sinkPath.getParent());
            } catch (IOException e) {
                throw new IllegalStateException("审计目录不可创建: " + sinkPath, e);
            }
        }
    }

    public void record(String tool, String event, String outcome, long durationMs, Map<String, Object> detail) {
        AuditEvent e = new AuditEvent(Instant.now(), tool, event, outcome, durationMs, detail);
        events.add(e);
        if (sinkPath != null) {
            try {
                Files.writeString(sinkPath, e.toJsonLine() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ioe) {
                throw new IllegalStateException("审计落盘失败（不静默）: " + sinkPath, ioe);
            }
        }
    }

    /** 回放：按工具读内存轨迹；进程重启后从 JSONL 逐行读回（可回放的事实基础）。 */
    public List<String> replayFromFile() throws IOException {
        if (sinkPath == null || !Files.exists(sinkPath)) return List.of();
        return Files.readAllLines(sinkPath, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
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
