package com.nontracey.aiservice.interview;

import com.nontracey.aiservice.dto.Dtos.Question;
import com.nontracey.aiservice.dto.Dtos.QuestionData;
import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.Loader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 出题:直接返回 topic 的 recallPrompts(已人工撰写)。 */
@Service
public class QuestionService {

    private final Loader loader;

    public QuestionService(Loader loader) {
        this.loader = loader;
    }

    public QuestionData generate(String topicId, Integer difficulty, int count) {
        Topic t = loader.get(topicId);
        if (t == null) throw new IllegalArgumentException("topic 不存在:" + topicId);

        List<Map<String, Object>> prompts = new ArrayList<>(t.recallPrompts());
        if (difficulty != null) {
            prompts = prompts.stream()
                    .filter(p -> p.get("difficulty") instanceof Number n && n.intValue() == difficulty)
                    .toList();
        }
        List<Question> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, prompts.size()); i++) {
            Map<String, Object> p = prompts.get(i);
            String qid = (String) p.getOrDefault("id", topicId + ".recall." + (i + 1));
            out.add(new Question(qid, (String) p.getOrDefault("prompt", ""),
                    p.get("difficulty") instanceof Number n ? n.intValue() : t.difficulty()));
        }
        return new QuestionData(out);
    }
}
