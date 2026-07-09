package com.nontracey.aiservice.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nontracey.aiservice.dto.Dtos.Evaluation;
import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.*;

/** LLM-as-judge 评估:按 topic 的 rubric 结构化打分,temperature=0 保证可复现。
 * 用 ChatClient + BeanOutputConverter:把 record 的 JSON Schema 自动塞进 Prompt,
 * 再把模型输出反序列化成强类型 EvalOutput(比手写 Jackson 解析更稳、编译期类型保证)。
 * 解析失败降级(degraded=true)。 */
@Service
public class EvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorService.class);

    /** LLM 输出契约(字段名即 JSON key,与 SYSTEM 模板里写的 JSON 结构一致)。 */
    public record EvalOutput(
            int score,
            Map<String, Integer> dimension_scores,
            List<String> hit_points,
            List<String> missed,
            List<String> mistakes,
            String feedback
    ) {}

    /** BeanOutputConverter 一次性构造,复用 schema 生成结果。 */
    private static final BeanOutputConverter<EvalOutput> CONVERTER = new BeanOutputConverter<>(EvalOutput.class);
    private static final String FORMAT = CONVERTER.getFormat();

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
        // BeanOutputConverter 的 format 描述(含 JSON Schema)拼到 system 末尾,让模型按 schema 输出
        String systemWithFormat = system + "\n输出格式:\n" + FORMAT;

        // 找题面
        String questionText = "";
        for (Map<String, Object> p : t.recallPrompts()) {
            if (questionId.equals(p.get("id"))) {
                questionText = (String) p.getOrDefault("prompt", "");
                break;
            }
        }

        String userMsg = "题目:" + questionText + "\n\n候选人回答:\n" + userAnswer;
        EvalOutput out;
        try {
            // .entity(EvalOutput.class) 内部用 BeanOutputConverter 反序列化模型输出
            out = chatClient.prompt()
                    .system(s -> s.text(systemWithFormat))
                    .user(userMsg)
                    .call()
                    .entity(EvalOutput.class);
        } catch (Exception e) {
            log.error("评估 LLM 调用失败,降级:{}", e.getMessage());
            return degraded("评估服务暂时不可用:" + e.getMessage());
        }

        if (out == null) {
            log.warn("评估 entity() 返回 null(模型输出无法解析),降级");
            return degraded("评估输出无法解析为结构化 JSON");
        }
        return toEvaluation(out);
    }

    private static Evaluation toEvaluation(EvalOutput o) {
        int score = Math.max(0, Math.min(100, o.score()));
        return new Evaluation(
                score,
                o.dimension_scores() == null ? Map.of() : o.dimension_scores(),
                o.hit_points() == null ? List.of() : o.hit_points(),
                o.missed() == null ? List.of() : o.missed(),
                o.mistakes() == null ? List.of() : o.mistakes(),
                o.feedback() == null ? "" : o.feedback(),
                false
        );
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
