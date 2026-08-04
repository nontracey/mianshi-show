package com.nontracey.aiservice.agent;

import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.HybridRetriever;
import com.nontracey.aiservice.rag.Loader;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 工具集(与 B 的 tools.py 对应):search_knowledge / get_scoring_rubric / save_note。
 *
 * <p><b>架构位置</b>:agent 模块。这些工具既被 {@link AgentOrchestrator} 显式编排调用,
 * 也被包装成 Spring AI {@code FunctionCallback} 供 LLM 通过 Function Calling 自主调用,
 * 一套实现两种用法,展示 SpringAI 工具机制。
 *
 * <p><b>关键设计——ThreadLocal 旁路取回检索结果</b>:当 LLM 通过 Function Calling 调
 * search_knowledge 时,方法返回值会被序列化送回给 LLM(作为工具输出),编排器拿不到。
 * 因此 searchKnowledge 额外把 docs 存进 {@link #LAST_RETRIEVED}(ThreadLocal),
 * retrieve 节点在 LLM 调用结束后从这里旁路取回真正的检索结果。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/agent/tools.py}。
 */
@Service
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private final Loader loader;
    private final HybridRetriever retriever;
    /** save_note 的学习笔记列表(内存存储,线程安全)。 */
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    /** ThreadLocal:LLM 调 search_knowledge 后,把 docs 存这里供编排器旁路取回。 */
    private static final ThreadLocal<List<ScoredDoc>> LAST_RETRIEVED = new ThreadLocal<>();

    public AgentTools(Loader loader, HybridRetriever retriever) {
        this.loader = loader;
        this.retriever = retriever;
    }

    /**
     * search_knowledge 工具:混合检索知识库。
     *
     * <p>除返回结果(给 LLM 或显式调用方)外,还把 docs 存入 ThreadLocal 供编排器旁路取回。
     *
     * @param query 检索关键词
     * @param topK  返回条数
     * @return 带分数的检索结果
     */
    public List<ScoredDoc> searchKnowledge(String query, int topK) {
        log.info("[tool] search_knowledge: query={}, topK={}", query, topK);
        List<ScoredDoc> docs = retriever.retrieve(query, topK, "hybrid");
        // 旁路保存:Function Calling 场景下返回值只给 LLM,编排器从这里取
        LAST_RETRIEVED.set(docs);
        return docs;
    }

    /**
     * get_scoring_rubric 工具:按题目 id 反查所属 topic 的评分标准。
     *
     * @param questionId 题目 id
     * @return 含 topic_id/title/rubric 的 Map;topic 不存在时返回带 error 的 Map
     */
    public Map<String, Object> getScoringRubric(String questionId) {
        log.info("[tool] get_scoring_rubric: qid={}", questionId);
        String topicId = com.nontracey.aiservice.interview.EvaluatorService.extractTopicId(questionId);
        Topic t = loader.get(topicId);
        if (t == null) return Map.of("error", "topic 不存在:" + topicId);
        return Map.of("topic_id", t.id(), "title", t.title(), "rubric", t.rubric());
    }

    /**
     * save_note 工具:把一条学习笔记存到内存列表。
     *
     * @param text 笔记内容
     * @return 含 saved/length/total 的结果 Map
     */
    public Map<String, Object> saveNote(String text) {
        log.info("[tool] save_note: len={}", text.length());
        notes.add(text);
        return Map.of("saved", true, "length", text.length(), "total", notes.size());
    }

    /** 当前已保存的全部学习笔记(供编排器做兜底判断与运维查看)。 */
    public List<String> notes() { return notes; }

    /** 取回最近一次 search_knowledge 的检索结果(可能为 null)。 */
    public static List<ScoredDoc> lastRetrieved() { return LAST_RETRIEVED.get(); }
    /** 清理 ThreadLocal,避免线程复用导致取到上一次的检索结果。 */
    public static void clearLastRetrieved() { LAST_RETRIEVED.remove(); }

    // ---------- Function Calling input records(供 FunctionCallback 包装用) ----------
    /** search_knowledge 的入参契约:query 必填,topK 可选(为 null 时编排器兜底 4)。 */
    public record SearchKnowledgeInput(String query, Integer topK) {}
    /** get_scoring_rubric 的入参契约。 */
    public record GetRubricInput(String questionId) {}
    /** save_note 的入参契约。 */
    public record SaveNoteInput(String text) {}
}
