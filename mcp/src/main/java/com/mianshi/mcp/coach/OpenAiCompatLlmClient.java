package com.mianshi.mcp.coach;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容端点客户端（免费模型即可）。超时/重试由 GovernanceExecutor 管，这里只发请求。
 * 已知短板：未做流式；未做端点轮换（客户端侧已有，服务端 MVP 未复刻）。
 */
@Component
public class OpenAiCompatLlmClient {

    public static final class RetryableLlmException extends RuntimeException {
        public RetryableLlmException(String msg) { super(msg); }
    }

    public record LlmResult(String content, long promptTokens, long completionTokens) {}

    private final RestClient restClient;
    private final String defaultModel;

    public OpenAiCompatLlmClient(
            @Value("${mcp.coach.base-url:}") String baseUrl,
            @Value("${mcp.coach.api-key:}") String apiKey,
            @Value("${mcp.coach.timeout-ms:20000}") long timeoutMs,
            @Value("${mcp.coach.model:}") String defaultModel) {
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        this.restClient = (baseUrl == null || baseUrl.isBlank())
                ? null
                : RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                        .build();
    }

    public boolean configured() { return restClient != null; }

    public String model() { return defaultModel; }

    /** 发起 chat/completions；5xx/网络类抛 RetryableLlmException 交治理层重试。 */
    public LlmResult complete(String model, JsonNode messages) {
        if (restClient == null) throw new IllegalStateException("coach_not_configured");
        var body = java.util.Map.of("model", model, "messages", messages, "stream", false);
        JsonNode resp = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (resp == null) throw new RetryableLlmException("empty response");
        JsonNode err = resp.path("error");
        if (!err.isMissingNode()) {
            String msg = err.path("message").asText("unknown");
            throw new RetryableLlmException("upstream error: " + msg);
        }
        String content = resp.path("choices").path(0).path("message").path("content").asText("");
        long prompt = resp.path("usage").path("prompt_tokens").asLong(0);
        long completion = resp.path("usage").path("completion_tokens").asLong(0);
        return new LlmResult(content, prompt, completion);
    }
}
