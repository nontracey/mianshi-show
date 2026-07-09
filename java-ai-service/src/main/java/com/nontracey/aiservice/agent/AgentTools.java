package com.nontracey.aiservice.agent;

import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.HybridRetriever;
import com.nontracey.aiservice.rag.Loader;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/** Agent 工具集(与 B 的 tools.py 对应):search_knowledge / get_scoring_rubric / save_note。
 * <p>方法本身是普通 Java 调用(供 AgentOrchestrator 显式编排用);
 * 同时暴露 input record,供 AgentOrchestrator 包成 Spring AI {@code FunctionCallback}
 * (让 LLM 通过 Function Calling 调用,展示 SpringAI 工具机制)。
 * <p>search_knowledge 内部把检索结果存 ThreadLocal,供 retrieve 节点在 LLM 调用后取回 docs。 */
@Service
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private final Loader loader;
    private final HybridRetriever retriever;
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    /** ThreadLocal:LLM 调 search_knowledge 后,把 docs 存这里供编排器取回。 */
    private static final ThreadLocal<List<ScoredDoc>> LAST_RETRIEVED = new ThreadLocal<>();

    public AgentTools(Loader loader, HybridRetriever retriever) {
        this.loader = loader;
        this.retriever = retriever;
    }

    /** search_knowledge:检索知识库。 */
    public List<ScoredDoc> searchKnowledge(String query, int topK) {
        log.info("[tool] search_knowledge: query={}, topK={}", query, topK);
        List<ScoredDoc> docs = retriever.retrieve(query, topK, "hybrid");
        LAST_RETRIEVED.set(docs);
        return docs;
    }

    /** get_scoring_rubric:查评分标准。 */
    public Map<String, Object> getScoringRubric(String questionId) {
        log.info("[tool] get_scoring_rubric: qid={}", questionId);
        String topicId = com.nontracey.aiservice.interview.EvaluatorService.extractTopicId(questionId);
        Topic t = loader.get(topicId);
        if (t == null) return Map.of("error", "topic 不存在:" + topicId);
        return Map.of("topic_id", t.id(), "title", t.title(), "rubric", t.rubric());
    }

    /** save_note:记笔记(内存)。 */
    public Map<String, Object> saveNote(String text) {
        log.info("[tool] save_note: len={}", text.length());
        notes.add(text);
        return Map.of("saved", true, "length", text.length(), "total", notes.size());
    }

    public List<String> notes() { return notes; }

    public static List<ScoredDoc> lastRetrieved() { return LAST_RETRIEVED.get(); }
    public static void clearLastRetrieved() { LAST_RETRIEVED.remove(); }

    // ---------- Function Calling input records(供 FunctionCallback 包装用) ----------
    public record SearchKnowledgeInput(String query, Integer topK) {}
    public record GetRubricInput(String questionId) {}
    public record SaveNoteInput(String text) {}
}
