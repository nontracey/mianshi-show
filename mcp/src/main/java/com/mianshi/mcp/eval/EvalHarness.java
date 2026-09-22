package com.mianshi.mcp.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.search.SearchService;

/** 评测执行：对照组跑分 + 三维分 + bad case + 置信度；工作区失败回滚（ADR-4/ADR-7）。 */
@Component
public class EvalHarness {

    private final SearchService search;
    private final AuditLog audit;
    private final Path workspaceRoot;

    public EvalHarness(SearchService search, AuditLog audit,
                       @Value("${mcp.eval.workspace:data/eval-workspace}") String workspaceRoot) {
        this.search = search;
        this.audit = audit;
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public EvalProtocol.Responder deterministicResponder() {
        return (question, withTools) -> {
            if (!withTools) {
                // 基线组：没有工具，只能凭问题文本本身——这正是"带工具增益"要量的差距
                return new EvalProtocol.Responder.Response("依据不足，需要检索支持。", "", true);
            }
            var hits = search.search(question, 1);
            if (hits.isEmpty()) {
                return new EvalProtocol.Responder.Response("依据不足，需要检索支持。", "", true);
            }
            var top = hits.get(0);
            return new EvalProtocol.Responder.Response(top.snippet(), top.source(), true);
        };
    }

    public EvalProtocol.RunReport run(String runId, List<EvalProtocol.GoldenItem> items,
                                      EvalProtocol.Responder responder) {
        Path ws = prepareWorkspace(runId);
        long start = System.nanoTime();
        List<EvalProtocol.ItemResult> details = new ArrayList<>();
        List<String> badCases = new ArrayList<>();
        try {
            for (EvalProtocol.GoldenItem item : items) {
                var base = responder.answer(item.question(), false);
                var aug = responder.answer(item.question(), true);
                boolean functionPass = base.schemaValid() && aug.schemaValid()
                        && aug.answer() != null && !aug.answer().isBlank();
                boolean gainHit = aug.answer() != null && item.expectKeywords().stream().anyMatch(aug.answer()::contains);
                boolean safetyPass = !audit.hasUnsafeEvent("eval-run");
                details.add(new EvalProtocol.ItemResult(item.id(), functionPass, gainHit, safetyPass,
                        base.answer(), aug.answer(), aug.evidenceSource()));
                if (!gainHit) {
                    badCases.add(item.id() + ": 增益未命中期望关键词" + item.expectKeywords()
                            + "；增强答=" + firstLine(aug.answer()));
                }
                writeOutput(ws, item.id(), base, aug);
            }
            long function = details.stream().filter(EvalProtocol.ItemResult::functionPass).count();
            long gain = details.stream().filter(EvalProtocol.ItemResult::gainHit).count();
            long safety = details.stream().filter(EvalProtocol.ItemResult::safetyPass).count();
            // 置信度：证据缺失条目越多，置信度越低（ADR-4）
            long withEvidence = details.stream().filter(r -> !r.evidence().isBlank()).count();
            String confidence = withEvidence == items.size() ? "high"
                    : withEvidence >= items.size() / 2 ? "medium" : "low";
            long ms = (System.nanoTime() - start) / 1_000_000;
            audit.record("eval-run", "run", "success", ms,
                    Map.of("runId", runId, "items", items.size(), "gain", gain, "function", function));
            return new EvalProtocol.RunReport(items.size(), function, gain, safety, details, badCases, confidence);
        } catch (RuntimeException e) {
            rollback(runId);
            throw e;
        }
    }

    private Path prepareWorkspace(String runId) {
        try {
            Path ws = workspaceRoot.resolve(runId);
            if (Files.exists(ws)) throw new IllegalStateException("runId 已存在，禁止覆盖（证据不可静默改写）: " + runId);
            Files.createDirectories(ws);
            return ws;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeOutput(Path ws, String itemId,
                             EvalProtocol.Responder.Response base, EvalProtocol.Responder.Response aug) {
        try {
            Files.writeString(ws.resolve(itemId + ".md"),
                    "# " + itemId + "\n\n## base（无工具）\n" + base.answer()
                    + "\n\n## augmented（带工具）\n" + aug.answer()
                    + "\n\n证据来源: " + aug.evidenceSource() + "\n",
                    java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 回滚：删除整个 runId 工作区，审计留痕。 */
    public void rollback(String runId) {
        Path ws = workspaceRoot.resolve(runId);
        if (Files.exists(ws)) {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // 清理失败也要留痕，不静默
            }
            audit.record("eval-run", "rollback", "success", 0, Map.of("runId", runId));
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
