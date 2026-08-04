package com.nontracey.aiservice.interview;

import com.nontracey.aiservice.dto.Dtos.Question;
import com.nontracey.aiservice.dto.Dtos.QuestionData;
import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.Loader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 出题服务:直接返回 topic 的 recallPrompts(已人工撰写),不调用 LLM 生成。
 *
 * <p><b>架构位置</b>:interview 模块。被 InterviewController(/api/interview/question)与
 * AgentOrchestrator(ask 节点)调用。
 *
 * <p><b>设计点</b>:题目来自知识库人工维护的 recallPrompts,质量可控、可复现,且零 LLM 成本;
 * 支持按难度过滤与按数量截取。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/interview/question_gen.py}。
 */
@Service
public class QuestionService {

    private final Loader loader;

    public QuestionService(Loader loader) {
        this.loader = loader;
    }

    /**
     * 为指定 topic 出题。
     *
     * @param topicId    topic id
     * @param difficulty 可选难度过滤;null 表示不过滤
     * @param count      期望题目数量;实际返回不超过该 topic 可用题数
     * @return 题目列表
     * @throws IllegalArgumentException topic 不存在
     */
    public QuestionData generate(String topicId, Integer difficulty, int count) {
        Topic t = loader.get(topicId);
        if (t == null) throw new IllegalArgumentException("topic 不存在:" + topicId);

        // 复制一份,避免过滤操作影响 Loader 内部数据
        List<Map<String, Object>> prompts = new ArrayList<>(t.recallPrompts());
        if (difficulty != null) {
            // 按难度精确过滤(difficulty 字段为数值类型才参与比较)
            prompts = prompts.stream()
                    .filter(p -> p.get("difficulty") instanceof Number n && n.intValue() == difficulty)
                    .toList();
        }
        List<Question> out = new ArrayList<>();
        // 截取前 count 条;不足则返回全部可用题
        for (int i = 0; i < Math.min(count, prompts.size()); i++) {
            Map<String, Object> p = prompts.get(i);
            // qid 缺省时用 topicId.recall.N 兜底,保证唯一
            String qid = (String) p.getOrDefault("id", topicId + ".recall." + (i + 1));
            out.add(new Question(qid, (String) p.getOrDefault("prompt", ""),
                    p.get("difficulty") instanceof Number n ? n.intValue() : t.difficulty()));
        }
        return new QuestionData(out);
    }
}
