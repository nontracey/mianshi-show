package com.nontracey.aiservice.agent;

import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.dto.Dtos.Topic;
import com.nontracey.aiservice.rag.HybridRetriever;
import com.nontracey.aiservice.rag.Loader;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/** Agent 工具集(显式调用版;后续可替换为 Spring AI @Tool 自动 Function Calling)。
 * 与 B 的 tools.py 对应:search_knowledge / get_scoring_rubric / save_note。 */
@Service
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private final Loader loader;
    private final HybridRetriever retriever;
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    public AgentTools(Loader loader, HybridRetriever retriever) {
        this.loader = loader;
        this.retriever = retriever;
    }

    /** search_knowledge:检索知识库。 */
    public List<ScoredDoc> searchKnowledge(String query, int topK) {
        log.info("[tool] search_knowledge: query={}, topK={}", query, topK);
        return retriever.retrieve(query, topK, "hybrid");
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
}
