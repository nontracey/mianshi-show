package com.mianshi.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.governance.GovernanceExecutor;
import com.mianshi.mcp.search.SearchService;
import com.mianshi.mcp.skill.SkillManifestService;

/**
 * 工具 1：题目/知识检索。治理三件套走 GovernanceExecutor；调用前校验 skill manifest 漂移（ADR-3）。
 */
@Component
public class QuestionSearchTool {

    private final SearchService search;
    private final GovernanceExecutor governance;
    private final AuditLog audit;
    private final SkillManifestService manifest;

    public QuestionSearchTool(SearchService search, GovernanceExecutor governance,
                              AuditLog audit, SkillManifestService manifest) {
        this.search = search;
        this.governance = governance;
        this.audit = audit;
        this.manifest = manifest;
        manifest.rescan("question-search", "v1:keyword-inmemory");
    }

    @Tool(description = "在面试训练知识库中检索题目与知识条目，返回带来源的片段列表")
    public String searchQuestions(
            @ToolParam(description = "检索词，支持中英文混合") String query,
            @ToolParam(description = "返回条数，默认 3") Integer topK) {
        int k = topK == null || topK <= 0 ? 3 : Math.min(topK, 10);
        var contract = new ToolContract("QUESTION_SEARCH", "v1", 5000, 1, "knowledge:read", true);
        Object result = governance.execute(contract, "q:" + query, () -> {
            if (!manifest.isSafeToInvoke("question-search", "v1:keyword-inmemory")) {
                audit.record("QUESTION_SEARCH", "drift_check", "unsafe", 0, Map.of());
                throw new IllegalStateException("skill_drifted: 内容已变更且未重扫，拒绝调用");
            }
            return search.search(query, k);
        });
        var hits = (List<SearchService.SearchHit>) result;
        if (hits.isEmpty()) return "{\"hits\":[],\"note\":\"无命中，可换检索词\"}";
        return hits.stream()
                .map(h -> "{\"id\":\"%s\",\"score\":%.1f,\"source\":\"%s\",\"snippet\":\"%s\"}"
                        .formatted(h.id(), h.score(), h.source(),
                                h.snippet().replace("\"", "'").replace("\n", " ")))
                .collect(Collectors.joining(",", "{\"hits\":[", "]}"));
    }
}
