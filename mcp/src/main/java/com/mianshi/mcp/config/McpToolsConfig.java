package com.mianshi.mcp.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mianshi.mcp.coach.CoachSessionTool;
import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.governance.GovernanceExecutor;
import com.mianshi.mcp.skill.SkillManifestService;
import com.mianshi.mcp.tool.CoachSessionToolEndpoint;
import com.mianshi.mcp.tool.EvalRunTool;
import com.mianshi.mcp.tool.QuestionSearchTool;

/** 装配：审计/治理/manifest 单例 + 三个工具注册进 MCP（协议层由 starter 自动暴露）。 */
@Configuration
public class McpToolsConfig {

    @Bean
    public AuditLog auditLog() { return new AuditLog(); }

    @Bean
    public GovernanceExecutor governanceExecutor(AuditLog audit) { return new GovernanceExecutor(audit); }

    @Bean
    public SkillManifestService skillManifestService() { return new SkillManifestService(); }

    @Bean
    public com.mianshi.mcp.cost.CostLedger costLedger() { return new com.mianshi.mcp.cost.CostLedger(); }

    @Bean
    public CoachSessionTool coachSessionTool(com.mianshi.mcp.coach.OpenAiCompatLlmClient llm,
                                             AuditLog audit,
                                             com.mianshi.mcp.cost.CostLedger cost) {
        return new CoachSessionTool(llm, audit, cost, "data/sessions", 2000);
    }

    @Bean
    public ToolCallbackProvider mcpTools(QuestionSearchTool q, EvalRunTool e, CoachSessionToolEndpoint c) {
        return MethodToolCallbackProvider.builder().toolObjects(q, e, c).build();
    }
}
