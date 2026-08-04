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

/**
 * LLM-as-Judge 评估服务:按 topic 的 rubric 对候选人回答结构化打分,temperature=0 保证可复现。
 *
 * <p><b>架构位置</b>:interview 模块。被 InterviewController(/api/interview/evaluate)与
 * AgentOrchestrator(evaluate 节点)调用。
 *
 * <p><b>核心机制</b>:用 ChatClient + {@link BeanOutputConverter}——把 record 的 JSON Schema 自动
 * 塞进 Prompt(format),再把模型输出反序列化成强类型 {@link EvalOutput}(比手写 Jackson 解析更稳、
 * 编译期类型保证)。
 *
 * <p><b>版本要点(Spring AI M4)</b>:M4 的 {@code .entity()} 不会自动注入 schema,需要手动
 * {@code new BeanOutputConverter<>(...).getFormat()} 拼到 system prompt 末尾(GA 版才自动注入)。
 *
 * <p><b>降级语义</b>:LLM 调用失败或输出无法解析时,返回 score=0 且 degraded=true 的占位结果,
 * 调用方据此知道本次评估质量不保证。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/interview/evaluator.py}。
 */
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

    /**
     * 评估一道题的候选人回答。
     *
     * <p>步骤:由 questionId 反推 topicId -> 取 topic 与 rubric -> 组装评分 system prompt
     * (含 JSON Schema) -> 调 LLM -> 反序列化为 {@link EvalOutput} -> 归一化成 {@link Evaluation}。
     *
     * @param questionId 题目 id(形如 topicId.recall.N)
     * @param userAnswer 候选人作答文本
     * @return 评估结果;LLM 失败/输出非法时为降级结果(degraded=true)
     * @throws IllegalArgumentException topic 不存在或缺少 rubric.mustHave
     */
    public Evaluation evaluate(String questionId, String userAnswer) {
        // 从 questionId 反推 topicId(去掉末尾 .recall.N)
        String topicId = extractTopicId(questionId);
        Topic t = loader.get(topicId);
        if (t == null) throw new IllegalArgumentException("topic 不存在:" + topicId);

        // rubric.mustHave 是评分的必答点,缺失则无法客观评估,直接拒绝
        Map<String, Object> rubric = t.rubric();
        if (rubric == null || !rubric.containsKey("mustHave")) {
            throw new IllegalArgumentException("topic 缺少 rubric.mustHave,无法评估:" + topicId);
        }

        // 把 rubric 的权重/必答点/加分点/常见错误填进 system 模板
        String system = SYSTEM.formatted(
                rubric.getOrDefault("scoreWeights", Map.of()),
                rubric.getOrDefault("mustHave", List.of()),
                rubric.getOrDefault("goodToHave", List.of()),
                rubric.getOrDefault("commonMistakes", List.of())
        );
        // BeanOutputConverter 的 format 描述(含 JSON Schema)拼到 system 末尾,让模型按 schema 输出
        String systemWithFormat = system + "\n输出格式:\n" + FORMAT;

        // 找题面:从 recallPrompts 中按 id 匹配,拿到原始题干供模型对照评分
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
            // LLM 调用异常:降级,保证接口可用
            log.error("评估 LLM 调用失败,降级:{}", e.getMessage());
            return degraded("评估服务暂时不可用:" + e.getMessage());
        }

        if (out == null) {
            // 模型输出无法解析成 EvalOutput:同样降级
            log.warn("评估 entity() 返回 null(模型输出无法解析),降级");
            return degraded("评估输出无法解析为结构化 JSON");
        }
        return toEvaluation(out);
    }

    /**
     * 把 LLM 输出的 {@link EvalOutput} 归一化成对外 {@link Evaluation}。
     *
     * <p>分数钳制到 [0,100];各集合字段为 null 时用空集合兜底,避免 NPE。
     *
     * @param o LLM 结构化输出
     * @return 对外评估结果(degraded=false)
     */
    private static Evaluation toEvaluation(EvalOutput o) {
        // 钳制总分到合法区间,防止模型输出越界值
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

    /**
     * 构造降级结果:score=0、空明细、degraded=true,feedback 说明降级原因。
     *
     * @param feedback 降级原因描述
     * @return 降级评估结果
     */
    private Evaluation degraded(String feedback) {
        return new Evaluation(0, Map.of(), List.of(), List.of(), List.of(), feedback, true);
    }

    /**
     * 从 questionId 反推 topicId。
     *
     * <p>规则:形如 {@code topicId.recall.N} 时去掉末尾两段;否则退化为去掉最后一个 {@code .} 之后的部分;
     * 完全不含 {@code .} 时原样返回(本身就是 topicId)。
     *
     * @param questionId 题目 id
     * @return topic id
     */
    public static String extractTopicId(String questionId) {
        String[] parts = questionId.split("\\.");
        // 标准格式:<topicId 若干段>.recall.<N>:去掉末尾 "recall" 与序号两段
        if (parts.length >= 3 && "recall".equals(parts[parts.length - 2])) {
            return String.join(".", Arrays.copyOf(parts, parts.length - 2));
        }
        // 兜底:去掉最后一个点之后的部分;无点则原样返回
        return questionId.contains(".") ? questionId.substring(0, questionId.lastIndexOf('.')) : questionId;
    }
}
