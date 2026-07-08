package com.nontracey.aiservice.dto;

import java.util.List;
import java.util.Map;

/** 请求/响应 DTO,与 B/D 三语言同契约(见 docs/00 §4)。 */
public final class Dtos {

    private Dtos() {}

    // ---------- /health ----------
    public record HealthData(String status, String version, String llmModel, String vectorStore,
                             String kbSource, boolean llmReachable, boolean vectorStoreReady) {}

    // ---------- /api/ingest ----------
    public record IngestReq(String source) {}
    public record IngestData(int count, int chunks, String contentVersion) {}

    // ---------- /api/ask ----------
    public record AskReq(String question, Integer topK, boolean stream) {}
    public record Source(String id, String topic, double score, String cardType) {}
    public record AskData(String answer, List<Source> sources, Map<String, Object> usage) {
        public static AskData empty(String answer) {
            return new AskData(answer, List.of(), Map.of());
        }
    }

    // ---------- /api/interview/question ----------
    public record QuestionReq(String topic, Integer difficulty, int count) {}
    public record Question(String questionId, String prompt, int difficulty) {}
    public record QuestionData(List<Question> questions) {}

    // ---------- /api/interview/evaluate ----------
    public record EvaluateReq(String questionId, String userAnswer, boolean stream) {}
    public record Evaluation(int score, Map<String, Integer> dimensionScores,
                             List<String> hit, List<String> missed, List<String> mistakes,
                             String feedback, boolean degraded) {}
    public record EvaluateData(Evaluation evaluation) {}

    // ---------- /api/agent/session ----------
    public record AgentSessionReq(String topic, int rounds) {}

    // ---------- /api/metrics ----------
    public record MetricsData(int requestsTotal, int tokensTotal, int cacheHits, int cacheMisses,
                              double cacheHitRate, double avgLatencyMs, int llmCalls) {}

    // ---------- topic(知识库条目)----------
    public record Topic(String id, String domain, String category, String title, String summary,
                       List<String> tags, int difficulty, String status, String interviewFrequency,
                       String interviewerFocus, List<Map<String, Object>> learningCards,
                       List<Map<String, Object>> recallPrompts, Map<String, Object> rubric) {}
}
