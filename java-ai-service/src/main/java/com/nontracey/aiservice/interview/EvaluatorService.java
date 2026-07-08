package com.nontracey.aiservice.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nontracey.aiservice.dto.Dtos.Evaluation;
import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/** LLM-as-judge 评估:按 topic 的 rubric 结构化打分,temperature=0 保证可复现。
 * 用 ChatClient + 强制 JSON 输出;解析失败降级(degraded=true)。 */
@Service
public class EvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorService.class);
    private static final String SYSTEM = """
            你是资深技术面试官,按给定评分标准客观评估候选人回答,输出严格 JSON。
            评分维度与权重:%s
            评分标准:
            - 必答点(must_have):%s
            - 加分点(good_to_have):%s
            - 常见错误(common_mistakes):%s
            输出 JSON:{"score":0-100,"dimension_scores":{"coverage":0-100,"accuracy":0-100,
            "interviewExpression":0-100,"depth":0-100},"hit_points":[],"missed":[],"mistakes":[],"feedback":""}
            """;

    private final ChatClient chatClient;
    private final Loader loader;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvaluatorService(ChatClient chatClient, Loader loader) {
        this.chatClient = chatClient;
        this.loader = loader;
    }

    @SuppressWarnings("unchecked")
    public Evaluation evaluate(String questionId, String userAnswer) {
        String topicId = extractTopicId(questionId);
        Topic t = loader.get(topicId);
        if (t == null) throw new IllegalArgumentException("topic 不存在:" + topicId);

        Map<String, Object> rubric = t.rubric();
        if (rubric == null || !rubric.containsKey("mustHave")) {
            throw new IllegalArgumentException("topic 缺少 rubric.mustHave,无法评估:" + topicId);
        }

        String system = SYSTEM.formatted(
                rubric.getOrDefault("scoreWeights", Map.of()),
                rubric.getOrDefault("mustHave", List.of()),
                rubric.getOrDefault("goodToHave", List.of()),
                rubric.getOrDefault("commonMistakes", List.of())
        );

        // 找题面
        String questionText = "";
        for (Map<String, Object> p : t.recallPrompts()) {
            if (questionId.equals(p.get("id"))) {
                questionText = (String) p.getOrDefault("prompt", "");
                break;
            }
        }

        String userMsg = "题目:" + questionText + "\n\n候选人回答:\n" + userAnswer;
        String content;
        try {
            content = chatClient.prompt()
                    .system(s -> s.text(system))
                    .user(userMsg)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("评估 LLM 调用失败,降级:{}", e.getMessage());
            return degraded("评估服务暂时不可用:" + e.getMessage());
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = mapper.readValue(content, Map.class);
            return parse(obj);
        } catch (Exception e) {
            log.warn("评估 JSON 解析失败,降级:{}", e.getMessage());
            return degraded("评估输出非合法 JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private Evaluation parse(Map<String, Object> obj) {
        int score = obj.get("score") instanceof Number n ? n.intValue() : 0;
        score = Math.max(0, Math.min(100, score));
        Map<String, Integer> dim = new HashMap<>();
        Object d = obj.getOrDefault("dimension_scores", Map.of());
        if (d instanceof Map<?, ?> dm) {
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof Number v) {
                    dim.put(k, v.intValue());
                }
            }
        }
        return new Evaluation(
                score,
                dim,
                toStringList(obj.get("hit_points")),
                toStringList(obj.get("missed")),
                toStringList(obj.get("mistakes")),
                (String) obj.getOrDefault("feedback", ""),
                false
        );
    }

    private List<String> toStringList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }

    private Evaluation degraded(String feedback) {
        return new Evaluation(0, Map.of(), List.of(), List.of(), List.of(), feedback, true);
    }

    public static String extractTopicId(String questionId) {
        String[] parts = questionId.split("\\.");
        if (parts.length >= 3 && "recall".equals(parts[parts.length - 2])) {
            return String.join(".", Arrays.copyOf(parts, parts.length - 2));
        }
        return questionId.contains(".") ? questionId.substring(0, questionId.lastIndexOf('.')) : questionId;
    }
}
