package com.mianshi.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.mianshi.mcp.eval.EvalHarness;
import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.governance.GovernanceExecutor;
import com.mianshi.mcp.search.InMemoryKeywordSearch;
import com.mianshi.mcp.skill.SkillManifestService;
import com.mianshi.mcp.tool.CoachSessionToolEndpoint;
import com.mianshi.mcp.tool.EvalRunTool;
import com.mianshi.mcp.tool.QuestionSearchTool;
import com.mianshi.mcp.tool.EvalRunTool;
import com.mianshi.mcp.tool.QuestionSearchTool;
import com.mianshi.mcp.eval.EvalHarness;

/**
 * 外部验收回归（2026-09-22）：缺参必须返回结构化 JSON 错误，
 * 不允许出现空 text 块（spring-ai M3 缺省行为，严格客户端会拒收）。
 */
class MissingArgumentStructuredErrorTest {

    private final AuditLog audit = new AuditLog();
    private final GovernanceExecutor governance = new GovernanceExecutor(audit);
    private final SkillManifestService manifest = new SkillManifestService();

    @Test
    void searchWithoutQueryReturnsStructuredError() {
        var tool = new QuestionSearchTool(new InMemoryKeywordSearch(), governance, audit, manifest);
        String result = tool.searchQuestions(null, null);
        assertThat(result).contains("\"error\":\"missing_query\"").contains("hint");
    }

    @Test
    void evalWithoutRunIdReturnsStructuredError() {
        var tool = new EvalRunTool(
                new EvalHarness(new InMemoryKeywordSearch(), audit, "target/eval-workspace-missing-arg-test"),
                governance, audit, null);
        String result = tool.runEval(null);
        assertThat(result).contains("\"error\":\"missing_runId\"").contains("hint");
    }

    @Test
    void coachWithoutArgumentsReturnsStructuredError() {
        // 缺参在进入 session 调用前就返回，CoachSessionTool 不会被触达，可为 null
        var tool = new CoachSessionToolEndpoint(null, governance, audit, manifest);
        String result = tool.coachSession(null, null);
        assertThat(result).contains("\"error\":\"missing_arguments\"").contains("hint");
    }
}
