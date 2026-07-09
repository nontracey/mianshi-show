package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.dto.Dtos.AskData;
import com.nontracey.aiservice.dto.Dtos.Source;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;

/** 生成器:拼 context + System Prompt(防幻觉)-> ChatClient -> 答案 + 来源。
 * <p>两条路径:
 * <ul>
 *   <li>{@link #generate(String, List)}:手拼 context(给 hybrid/hybrid_rerank mode 用,
 *       保留混合检索亮点)。</li>
 *   <li>{@link #generateWithAdvisor(String, int)}:用 Spring AI {@link QuestionAnswerAdvisor}
 *       自动注入检索结果(走 {@link VectorStore#similaritySearch},展示 SpringAI 原生 Advisor 能力)。</li>
 * </ul> */
@Service
public class Generator {

    /** 手拼 context 路径的 system(含上下文占位符)。 */
    private static final String SYSTEM_WITH_CONTEXT = """
            你是严谨的技术面试知识助手。只依据【上下文】回答,标注来源条目 id。
            上下文没有的内容,直接说"知识库中没有相关内容",不要编造。
            回答结构:先直接答,再分点展开(若涉及),最后用 [来源:id] 标注引用。

            【上下文】
            %s
            """;

    /** Advisor 路径的 system(上下文由 QuestionAnswerAdvisor 自动注入到 user message)。 */
    private static final String SYSTEM_ADVISOR = """
            你是严谨的技术面试知识助手。只依据用户消息里【上下文】部分回答,标注来源条目 id。
            上下文没有的内容,直接说"知识库中没有相关内容",不要编造。
            回答结构:先直接答,再分点展开(若涉及),最后用 [来源:id] 标注引用。
            """;

    private final ChatClient chatClient;
    private final VectorStoreService vectorStore;

    public Generator(ChatClient chatClient, VectorStoreService vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    /** 手拼 context 路径:docs 由 HybridRetriever 提供(混合检索)。 */
    public AskData generate(String question, List<ScoredDoc> docs) {
        String context = buildContext(docs);
        String answer = chatClient.prompt()
                .system(SYSTEM_WITH_CONTEXT.formatted(context))
                .user(question)
                .call()
                .content();
        return new AskData(answer == null ? "" : answer, extractSources(docs), Map.of());
    }

    /** Advisor 路径:QuestionAnswerAdvisor 自动调 vectorStore.similaritySearch 检索 + 注入上下文。
     * 展示 SpringAI 原生 RAG 能力(纯向量检索,不跑 BM25/RRF)。 */
    public AskData generateWithAdvisor(String question, int topK) {
        QuestionAnswerAdvisor advisor = new QuestionAnswerAdvisor(
                vectorStore,
                SearchRequest.defaults().withTopK(topK).withSimilarityThreshold(0.0)
        );
        var resp = chatClient.prompt()
                .system(SYSTEM_ADVISOR)
                .advisors(advisor)
                .user(question)
                .call()
                .chatResponse();
        String answer = resp == null || resp.getResult() == null ? ""
                : resp.getResult().getOutput().getContent();
        return new AskData(answer == null ? "" : answer, List.of(), Map.of());
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
