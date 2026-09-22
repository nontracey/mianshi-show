package com.mianshi.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mianshi.mcp.eval.EvalHarness;
import com.mianshi.mcp.eval.EvalProtocol;
import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.governance.GovernanceExecutor;
import com.mianshi.mcp.search.InMemoryKeywordSearch;
import com.mianshi.mcp.skill.SkillManifestService;
import com.mianshi.mcp.tool.ToolContract;

class GovernanceAndEvalTest {

    private final AuditLog audit = new AuditLog();

    @Test
    void contractWithoutAuditIsRejected() {
        GovernanceExecutor g = new GovernanceExecutor(audit);
        var bad = new ToolContract("X", "v1", 1000, 0, "scope", false);
        assertThatThrownBy(() -> g.execute(bad, "k", () -> "ok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit");
    }

    @Test
    void timeoutIsRetryableAndAudited() {
        GovernanceExecutor g = new GovernanceExecutor(audit);
        var c = new ToolContract("SLOW", "v1", 50, 1, "scope", true);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> g.execute(c, "k", () -> {
            Thread.sleep(500);
            return "ok";
        })).isInstanceOf(GovernanceExecutor.RetryableException.class);
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(tookMs).isLessThan(500); // 两次 50ms 超时，不应等满两次 500ms
        assertThat(audit.byTool("SLOW")).hasSize(2);
        assertThat(audit.byTool("SLOW")).allMatch(e -> "failure".equals(e.outcome()));
    }

    @Test
    void businessFailureDoesNotRetry() {
        GovernanceExecutor g = new GovernanceExecutor(audit);
        var c = new ToolContract("BIZ", "v1", 1000, 3, "scope", true);
        assertThatThrownBy(() -> g.execute(c, "k", () -> {
            throw new IllegalArgumentException("bad input");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.byTool("BIZ")).hasSize(1); // 不可重试错误只执行一次
    }

    @Test
    void skillDriftBlocksInvocation() {
        SkillManifestService m = new SkillManifestService();
        m.rescan("t", "content-v1");
        assertThat(m.isSafeToInvoke("t", "content-v1")).isTrue();
        assertThat(m.isSafeToInvoke("t", "content-v2-injected")).isFalse(); // 漂移
        m.rescan("t", "content-v2-injected");
        assertThat(m.isSafeToInvoke("t", "content-v2-injected")).isTrue(); // 重扫放行
        assertThat(m.entries().get("t").version()).isEqualTo(2); // 版本只增不减
    }

    @Test
    void evalHarnessRunsControlGroupAndProducesThreeDimensionScores() {
        var search = new InMemoryKeywordSearch();
        var h = new EvalHarness(search, audit, "target/eval-workspace-test");
        var items = List.of(
                new EvalProtocol.GoldenItem("G1", "上线判据和黄金集发布门怎么设计？",
                        List.of("黄金集", "发布门"), "golden-set"),
                new EvalProtocol.GoldenItem("G2", "RRF 在混合检索里干什么？",
                        List.of("RRF"), "rrf-hybrid"),
                new EvalProtocol.GoldenItem("G3", "上下文超预算怎么处理？",
                        List.of("预算", "裁"), "context-budget"));
        var report = h.run("test-run-1", items, h.deterministicResponder());
        assertThat(report.functionScore()).isEqualTo(3);
        assertThat(report.gainScore()).isEqualTo(3); // 增强组应全命中（检索可用）
        assertThat(report.safetyScore()).isEqualTo(3);
        assertThat(report.confidence()).isEqualTo("high");
        // 对照意义：基线组全部"依据不足"，增益=带工具相对基线的差
        assertThat(report.details()).allSatisfy(d -> {
            assertThat(d.baseAnswer()).contains("依据不足");
            assertThat(d.evidence()).isNotBlank();
        });
    }

    @Test
    void evalRollbackRemovesWorkspaceOnFailure() {
        var search = new InMemoryKeywordSearch();
        var h = new EvalHarness(search, audit, "target/eval-workspace-test");
        assertThatThrownBy(() -> h.run("boom-run", null, h.deterministicResponder()));
        assertThat(java.nio.file.Path.of("target/eval-workspace-test/boom-run")).doesNotExist();
    }
}
