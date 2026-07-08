package com.nontracey.aiservice.agent;

import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.dto.Dtos.Evaluation;
import com.nontracey.aiservice.dto.Dtos.Question;
import com.nontracey.aiservice.dto.StreamEvent;
import com.nontracey.aiservice.interview.EvaluatorService;
import com.nontracey.aiservice.interview.QuestionService;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/** Agent 状态机编排器(与 B 的 graph.py 对应)。
 * 节点:retrieve(tool: search_knowledge) -> ask -> simulate -> evaluate -> decide -> followup/advise。
 * 工具被显式调用(深挖时可讲"agent 编排调用工具");后续可替换为 Spring AI 自动 Function Calling。 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentTools tools;
    private final QuestionService questionService;
    private final EvaluatorService evaluatorService;
    private final ChatClient chatClient;

    public AgentOrchestrator(AgentTools tools, QuestionService questionService,
                             EvaluatorService evaluatorService, ChatClient chatClient) {
        this.tools = tools;
        this.questionService = questionService;
        this.evaluatorService = evaluatorService;
        this.chatClient = chatClient;
    }

    public List<StreamEvent> run(String topic, int rounds) {
        List<StreamEvent> events = new ArrayList<>();
        rounds = Math.max(1, rounds);

        // 1. retrieve(调用 search_knowledge 工具)
        List<ScoredDoc> docs = tools.searchKnowledge(topic, 4);
        events.add(new StreamEvent("retrieve", Map.of(
                "tool_call", "search_knowledge",
                "docs_count", docs.size(),
                "docs", docs.stream().limit(3).map(d -> Map.of(
                        "topic_id", d.chunk().metadata().get("topic_id"),
                        "title", d.chunk().metadata().get("title"),
                        "score", Math.round(d.score() * 10000) / 10000.0)).toList()
        )));

        int round = 0;
        Evaluation lastEval = null;
        while (round < rounds) {
            round++;
            // 2. ask
            Dtos.QuestionData qd = questionService.generate(topic, null, 1);
            if (qd.questions().isEmpty()) {
                events.add(new StreamEvent("error", Map.of("msg", "topic 无 recallPrompts:" + topic)));
                return events;
            }
            Question q = qd.questions().get(0);
            events.add(new StreamEvent("question", Map.of(
                    "round", round, "question_id", q.questionId(),
                    "prompt", q.prompt(), "difficulty", q.difficulty())));

            // 3. simulate(LLM 模拟求职者回答)
            String answer;
            try {
                answer = chatClient.prompt()
                        .system(s -> s.text("你是有 3 年经验的中级工程师,正在面试。用第一人称回答(可有遗漏,别瞎编):\n题目:" + q.prompt()))
                        .user("请回答。")
                        .call()
                        .content();
                if (answer == null) answer = "";
            } catch (Exception e) {
                log.warn("模拟回答失败:{}", e.getMessage());
                answer = "(模拟回答失败)";
            }
            events.add(new StreamEvent("answer", Map.of("text", answer, "round", round)));

            // 4. evaluate
            try {
                lastEval = evaluatorService.evaluate(q.questionId(), answer);
            } catch (Exception e) {
                events.add(new StreamEvent("error", Map.of("msg", "评估失败:" + e.getMessage())));
                return events;
            }
            events.add(new StreamEvent("evaluate", Map.of(
                    "score", lastEval.score(), "missed", lastEval.missed(),
                    "mistakes", lastEval.mistakes(), "feedback", lastEval.feedback(),
                    "degraded", lastEval.degraded())));

            // 5. decide
            if (lastEval.score() < 70 && round < rounds) {
                events.add(new StreamEvent("followup", Map.of(
                        "round", round, "reason", "score=" + lastEval.score() + " < 70,继续追问")));
                continue;
            }
            break;
        }

        // 6. advise(调 save_note 工具)
        if (lastEval != null) {
            Evaluation evalRef = lastEval;
            String advice;
            try {
                String systemText = "你是面试教练。基于评估给 3 条学习建议,补足 missed。markdown 列表。\n"
                        + "评估:score=" + evalRef.score() + ", missed=" + evalRef.missed()
                        + ", mistakes=" + evalRef.mistakes();
                advice = chatClient.prompt()
                        .system(systemText)
                        .user("请给建议。")
                        .call()
                        .content();
            } catch (Exception e) {
                advice = "(建议生成失败:" + e.getMessage() + ")";
            }
            Map<String, Object> noteResult = tools.saveNote(advice);
            events.add(new StreamEvent("advise", Map.of("advice", advice, "note_saved", noteResult.get("saved"))));
        }

        events.add(new StreamEvent("done", Map.of("rounds_done", round)));
        return events;
    }
}
