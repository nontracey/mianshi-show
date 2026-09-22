package com.mianshi.mcp.coach;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mianshi.mcp.cost.CostLedger;
import com.mianshi.mcp.governance.AuditLog;

/**
 * Coach 会话工具：无状态代理一次教练问答（系统提示词内置教练约束 + 用户消息），
 * 输出经去噪（去控制符 + 限长），Token 进成本台账，全程审计，会话追加式落盘。
 */
public class CoachSessionTool {

    private final OpenAiCompatLlmClient llm;
    private final AuditLog audit;
    private final CostLedger cost;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path sessionDir;
    private final int maxChars;

    public CoachSessionTool(OpenAiCompatLlmClient llm, AuditLog audit, CostLedger cost,
                            @Value("${mcp.coach.session-dir:data/sessions}") String sessionDir,
                            @Value("${mcp.coach.max-answer-chars:2000}") int maxChars) {
        this.llm = llm;
        this.audit = audit;
        this.cost = cost;
        this.sessionDir = Path.of(sessionDir);
        this.maxChars = maxChars;
    }

    public static final String SYSTEM_PROMPT = """
            你是面试训练教练。规则：1) 只依据给到的资料回答，资料不足就明说；
            2) 不编造数字与经历；3) 回答控制在 300 字内，先结论后理由。
            """;

    public String call(String sessionId, String message) {
        long start = System.nanoTime();
        String outcome;
        String answer;
        try {
            if (!llm.configured()) {
                outcome = "not_configured";
                answer = "{\"error\":\"coach_not_configured\",\"hint\":\"设置 mcp.coach.base-url 与 model 后可用\"}";
                audit.record("coach-session", "call", outcome, elapsed(start), Map.of("sessionId", sessionId));
                return answer;
            }
            ObjectNode sys = mapper.createObjectNode().put("role", "system").put("content", SYSTEM_PROMPT);
            ObjectNode usr = mapper.createObjectNode().put("role", "user").put("content", message);
            ArrayNode msgs = mapper.createArrayNode();
            msgs.add(sys);
            msgs.add(usr);
            var result = llm.complete(llm.model(), msgs);
            cost.record("coach-session", result.promptTokens(), result.completionTokens());
            answer = denoise(result.content());
            outcome = "success";
            appendSession(sessionId, message, answer, result.promptTokens(), result.completionTokens());
            audit.record("coach-session", "call", outcome, elapsed(start),
                    Map.of("sessionId", sessionId, "promptTokens", result.promptTokens(),
                           "completionTokens", result.completionTokens()));
            return answer;
        } catch (RuntimeException e) {
            outcome = "failure";
            audit.record("coach-session", "call", outcome, elapsed(start),
                    Map.of("sessionId", sessionId, "error", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    /** 去噪：去控制符 + 限长（ADR：token 成本与上下文噪声治理的最低配版）。 */
    static String denoise(String text, int maxChars) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars) + "…" : cleaned;
    }

    private String denoise(String text) { return denoise(text, maxChars); }

    private void appendSession(String sessionId, String message, String answer, long p, long c) {
        try {
            Files.createDirectories(sessionDir);
            Path f = sessionDir.resolve("session-" + sessionId + ".jsonl");
            String line = mapper.writeValueAsString(Map.of(
                    "at", Instant.now().toString(), "message", message, "answer", answer,
                    "promptTokens", p, "completionTokens", c));
            // 追加式写入：会话日志只增不改（与"证据禁静默改写"同构）
            Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            audit.record("coach-session", "session_log", "failure", 0, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private long elapsed(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }
}
