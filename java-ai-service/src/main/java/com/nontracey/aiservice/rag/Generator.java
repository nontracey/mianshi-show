package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.dto.Dtos.AskData;
import com.nontracey.aiservice.dto.Dtos.Source;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/** 生成器:拼 context + System Prompt(防幻觉)-> ChatClient -> 答案 + 来源。 */
@Service
public class Generator {

    private static final String SYSTEM = """
            你是严谨的技术面试知识助手。只依据【上下文】回答,标注来源条目 id。
            上下文没有的内容,直接说"知识库中没有相关内容",不要编造。
            回答结构:先直接答,再分点展开(若涉及),最后用 [来源:id] 标注引用。

            【上下文】
            %s
            """;

    private final ChatClient chatClient;

    public Generator(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public AskData generate(String question, List<ScoredDoc> docs) {
        String context = buildContext(docs);
        String answer = chatClient.prompt()
                .system(SYSTEM.formatted(context))
                .user(question)
                .call()
                .content();
        return new AskData(answer == null ? "" : answer, extractSources(docs), Map.of());
    }

    private String buildContext(List<ScoredDoc> docs) {
        if (docs.isEmpty()) return "(空)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            ScoredDoc d = docs.get(i);
            Map<String, Object> m = d.chunk().metadata();
            sb.append("[").append(i + 1).append("] id=").append(m.get("topic_id"))
              .append(" | ").append(m.get("title")).append("(").append(m.get("card_type")).append(")\n")
              .append(d.chunk().text()).append("\n\n");
        }
        return sb.toString();
    }

    private List<Source> extractSources(List<ScoredDoc> docs) {
        Set<String> seen = new HashSet<>();
        List<Source> out = new ArrayList<>();
        for (ScoredDoc d : docs) {
            String tid = (String) d.chunk().metadata().get("topic_id");
            if (tid != null && !tid.isEmpty() && seen.add(tid)) {
                out.add(new Source(tid, (String) d.chunk().metadata().getOrDefault("title", ""),
                        Math.round(d.score() * 10000) / 10000.0,
                        (String) d.chunk().metadata().getOrDefault("card_type", "")));
            }
        }
        return out;
    }
}
