package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.dto.Dtos.AskData;
import com.nontracey.aiservice.dto.Dtos.Source;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 生成器(RAG 的生成环节):拼 context + System Prompt(防幻觉)-> ChatClient -> 答案 + 来源。
 *
 * <p><b>架构位置</b>:rag 模块出口,消费 HybridRetriever / VectorStoreService 的检索结果,产出
 * {@link AskData} 交给 RagController 返回。
 *
 * <p><b>防幻觉设计</b>:System Prompt 明确要求"只依据上下文回答、上下文没有就说没有、标注来源 id",
 * 约束模型不编造。
 *
 * <p><b>两条路径</b>:
 * <ul>
 *   <li>{@link #generate(String, List)}:手拼 context(给 hybrid/hybrid_rerank mode 用,
 *       保留混合检索亮点)。</li>
 *   <li>{@link #generateWithAdvisor(String, int)}:用 Spring AI {@link QuestionAnswerAdvisor}
 *       自动注入检索结果(走 {@link VectorStore#similaritySearch},展示 SpringAI 原生 Advisor 能力)。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/rag/generator.py}。
 */
@Service
public class Generator {

    /** 手拼 context 路径的 system(含上下文占位符 %s,由 buildContext 填充)。 */
    private static final String SYSTEM_WITH_CONTEXT = """
            你是严谨的技术面试知识助手。只依据【上下文】回答,标注来源条目 id。
            上下文没有的内容,直接说"知识库中没有相关内容",不要编造。
            回答结构:先直接答,再分点展开(若涉及),最后用 [来源:id] 标注引用。

            【上下文】
            %s
            """;

    /** Advisor 路径的 system(上下文由 QuestionAnswerAdvisor 自动注入到 user message,故此处不含占位符)。 */
    private static final String SYSTEM_ADVISOR = """
            你是严谨的技术面试知识助手。只依据用户消息里【上下文】部分回答,标注来源条目 id。
            上下文没有的内容,直接说"知识库中没有相关内容",不要编造。
            回答结构:先直接答,再分点展开(若涉及),最后用 [来源:id] 标注引用。
            """;

    private final ChatClient chatClient;
    /** advisor 模式下作为标准 VectorStore 传给 QuestionAnswerAdvisor。 */
    private final VectorStore vectorStore;

    public Generator(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    /**
     * 手拼 context 路径:docs 由 HybridRetriever 提供(混合检索),把检索结果拼进 system 再调 LLM。
     *
     * @param question 用户问题
     * @param docs     混合检索命中的 chunk 列表
     * @return 答案 + 来源(usage 为空)
     */
    public AskData generate(String question, List<ScoredDoc> docs) {
        String context = buildContext(docs);
        String answer = chatClient.prompt()
                .system(SYSTEM_WITH_CONTEXT.formatted(context))
                .user(question)
                .call()
                .content();
        return new AskData(answer == null ? "" : answer, extractSources(docs), Map.of());
    }

    /**
     * Advisor 路径:QuestionAnswerAdvisor 自动调 vectorStore.similaritySearch 检索 + 注入上下文。
     * 展示 SpringAI 原生 RAG 能力(纯向量检索,不跑 BM25/RRF)。
     *
     * <p>注意:该路径返回的来源列表为空(sources 由 Advisor 注入的上下文自带 id,此处不再单独提取)。
     *
     * @param question 用户问题
     * @param topK     Advisor 检索条数
     * @return 答案(sources/usage 为空)
     */
    public AskData generateWithAdvisor(String question, int topK) {
        // similarityThreshold 设 0.0:不按阈值过滤,完全交给 topK 控制返回数量
        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().query("")
                        .topK(topK).similarityThresholdAll().build())
                .build();
        var resp = chatClient.prompt()
                .system(SYSTEM_ADVISOR)
                .advisors(advisor)
                .user(question)
                .call()
                .chatResponse();
        String answer = resp == null || resp.getResult() == null ? ""
                : resp.getResult().getOutput().getText();
        return new AskData(answer == null ? "" : answer, List.of(), Map.of());
    }

    /**
     * 把检索结果拼成带编号的来源文本块,作为上下文注入 system prompt。
     *
     * @param docs 检索结果
     * @return 上下文文本;为空时返回 "(空)"
     */
    private String buildContext(List<ScoredDoc> docs) {
        if (docs.isEmpty()) return "(空)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            ScoredDoc d = docs.get(i);
            Map<String, Object> m = d.chunk().metadata();
            // 每条标注 [序号] + topic_id + 标题 + 卡片类型,便于模型在答案里引用
            sb.append("[").append(i + 1).append("] id=").append(m.get("topic_id"))
              .append(" | ").append(m.get("title")).append("(").append(m.get("card_type")).append(")\n")
              .append(d.chunk().text()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 从检索结果提取去重后的来源列表(按 topic_id 去重,保留首次出现的分数)。
     *
     * @param docs 检索结果
     * @return 来源列表(同一 topic 只出现一次)
     */
    private List<Source> extractSources(List<ScoredDoc> docs) {
        Set<String> seen = new HashSet<>();
        List<Source> out = new ArrayList<>();
        for (ScoredDoc d : docs) {
            String tid = (String) d.chunk().metadata().get("topic_id");
            // 按 topic_id 去重,避免同一 topic 的多个 chunk 重复出现在来源里
            if (tid != null && !tid.isEmpty() && seen.add(tid)) {
                out.add(new Source(tid, (String) d.chunk().metadata().getOrDefault("title", ""),
                        Math.round(d.score() * 10000) / 10000.0,
                        (String) d.chunk().metadata().getOrDefault("card_type", "")));
            }
        }
        return out;
    }
}
