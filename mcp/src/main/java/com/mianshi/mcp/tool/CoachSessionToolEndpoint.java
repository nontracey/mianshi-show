package com.mianshi.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.mianshi.mcp.coach.CoachSessionTool;
import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.governance.GovernanceExecutor;
import com.mianshi.mcp.governance.GovernanceExecutor.RetryableException;
import com.mianshi.mcp.skill.SkillManifestService;

/** 工具 3：Coach 会话（代理一次教练问答）。未配置端点时返回结构化错误，不静默。 */
@Component
public class CoachSessionToolEndpoint {

    private final CoachSessionTool session;
    private final GovernanceExecutor governance;
    private final AuditLog audit;
    private final SkillManifestService manifest;

    public CoachSessionToolEndpoint(CoachSessionTool session, GovernanceExecutor governance,
                                    AuditLog audit, SkillManifestService manifest) {
        this.session = session;
        this.governance = governance;
        this.audit = audit;
        this.manifest = manifest;
        manifest.rescan("coach-session", "v1:proxy-llm");
    }

    @Tool(description = "发起一次面试教练问答：输入会话 ID 与消息，返回教练回答（需要服务端已配置模型端点）")
    public String coachSession(
            @ToolParam(required = false, description = "会话 ID，用于会话日志归档") String sessionId,
            @ToolParam(required = false, description = "用户消息") String message) {
        // 外部验收发现（2026-09-22）：缺参错误统一改工具内校验 + 结构化返回（失败不静默）。
        if (sessionId == null || sessionId.isBlank() || message == null || message.isBlank()) {
            return "{\"error\":\"missing_arguments\",\"hint\":\"sessionId 与 message 必填\"}";
        }
        var contract = new ToolContract("COACH_SESSION", "v1", 30000, 2, "coach:ask", true);
        Object result = governance.execute(contract, "session:" + sessionId, () -> {
            if (!manifest.isSafeToInvoke("coach-session", "v1:proxy-llm")) {
                audit.record("COACH_SESSION", "drift_check", "unsafe", 0, Map.of());
                throw new IllegalStateException("skill_drifted: 内容已变更且未重扫，拒绝调用");
            }
            return session.call(sessionId, message);
        });
        String answer = String.valueOf(result);
        return "{\"answer\":\"" + answer.replace("\\", "\\\\").replace("\"", "'")
                .replace("\n", " ") + "\"}";
    }
}
