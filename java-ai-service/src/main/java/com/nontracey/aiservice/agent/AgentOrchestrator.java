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
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;

/** Agent 状态机编排器(与 B 的 graph.py 对应)。
 * <p>节点:retrieve(tool: search_knowledge) -> ask -> simulate -> evaluate -> decide -> followup/advise。
 * <p>retrieve/advise 节点用 Spring AI {@link FunctionCallback} 把工具暴露给 LLM,LLM 通过
 * Function Calling 调用(展示 SpringAI 工具机制);ask/simulate/evaluate 走显式编排(流程固定)。
 * <p>runFlux 返回 {@link Flux}&lt;{@link StreamEvent}&gt;,供 WebFlux SSE 端点真流式推送。 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentTools tools;
    private final QuestionService questionService;
    private final EvaluatorService evaluatorService;
    private final ChatClient chatClient;
    private final FunctionCallback searchCallback;
    private final FunctionCallback saveCallback;

    public AgentOrchestrator(AgentTools tools, QuestionService questionService,
                             EvaluatorService evaluatorService, ChatClient chatClient) {
        this.tools = tools;
        this.questionService = questionService;
        this.evaluatorService = evaluatorService;
        this.chatClient = chatClient;
        // 把 AgentTools 方法包成 FunctionCallback,LLM 可通过 Function Calling 调用
        this.searchCallback = FunctionCallback.builder()
                .description("检索面试知识库,返回与 query 相关的知识条目")
                .function("search_knowledge",
                        (AgentTools.SearchKnowledgeInput i) -> tools.searchKnowledge(
                                i.query(), i.topK() == null ? 4 : i.topK()))
                .inputType(AgentTools.SearchKnowledgeInput.class)
                .build();
        this.saveCallback = FunctionCallback.builder()
                .description("记一条学习笔记到本地")
                .function("save_note",
                        (AgentTools.SaveNoteInput i) -> tools.saveNote(i.text()))
                .inputType(AgentTools.SaveNoteInput.class)
                .build();
    }

    /** 流式跑一轮模拟面试,逐事件 yield(供 SSE)。 */
    public Flux<StreamEvent> runFlux(String topic, int rounds) {
        int actualRounds = Math.max(1, rounds);
        return Flux.create(sink -> {
            Thread worker = new Thread(() -> {
                try {
                    runInternal(topic, actualRounds, sink);
                    sink.complete();
                } catch (Exception e) {
                    sink.error(e);
                }
            }, "agent-orchestrator");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void runInternal(String topic, int rounds, FluxSink<StreamEvent> sink) {
        // 1. retrieve(LLM 调 search_knowledge 工具)
        AgentTools.clearLastRetrieved();
        List<ScoredDoc> docs;
        try {
            chatClient.prompt()
                    .system(s -> s.text("你是技术面试官。请调用 search_knowledge 工具检索 topic:" + topic
                            + " 的知识(query=" + topic + ", topK=4),了解重点后再出题。"))
                    .user("开始检索。")
                    .functions(searchCallback)
                    .call()
                    .content();
            docs = AgentTools.lastRetrieved();
        } catch (Exception e) {
            log.warn("retrieve Function Calling 失败,降级显式检索:{}", e.getMessage());
            docs = null;
        }
        if (docs == null || docs.isEmpty()) {
            // LLM 没调工具或调用失败,fallback 显式检索
            docs = tools.searchKnowledge(topic, 4);
        }
        final List<ScoredDoc> docsFinal = docs;
        sink.next(new StreamEvent("retrieve", Map.of(
                "tool_call", "search_knowledge",
                "docs_count", docsFinal.size(),
                "docs", docsFinal.stream().limit(3).map(d -> Map.of(
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
                sink.next(new StreamEvent("error", Map.of("msg", "topic 无 recallPrompts:" + topic)));
                return;
            }
            Question q = qd.questions().get(0);
            sink.next(new StreamEvent("question", Map.of(
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
            sink.next(new StreamEvent("answer", Map.of("text", answer, "round", round)));

            // 4. evaluate
            try {
                lastEval = evaluatorService.evaluate(q.questionId(), answer);
            } catch (Exception e) {
                sink.next(new StreamEvent("error", Map.of("msg", "评估失败:" + e.getMessage())));
                return;
            }
            sink.next(new StreamEvent("evaluate", Map.of(
                    "score", lastEval.score(), "missed", lastEval.missed(),
                    "mistakes", lastEval.mistakes(), "feedback", lastEval.feedback(),
                    "degraded", lastEval.degraded())));

            // 5. decide
            if (lastEval.score() < 70 && round < rounds) {
                sink.next(new StreamEvent("followup", Map.of(
                        "round", round, "reason", "score=" + lastEval.score() + " < 70,继续追问")));
                continue;
            }
            break;
        }

        // 6. advise(LLM 调 save_note 工具记笔记)
        if (lastEval != null) {
            Evaluation evalRef = lastEval;
            String advice;
            try {
                String systemText = "你是面试教练。基于评估给 3 条学习建议,补足 missed。\n"
                        + "评估:score=" + evalRef.score() + ", missed=" + evalRef.missed()
                        + ", mistakes=" + evalRef.mistakes()
                        + "\n给完建议后,调用 save_note 工具把建议原文保存(text=建议全文)。";
                advice = chatClient.prompt()
                        .system(systemText)
                        .user("请给建议并保存。")
                        .functions(saveCallback)
                        .call()
                        .content();
                if (advice == null) advice = "";
            } catch (Exception e) {
                advice = "(建议生成失败:" + e.getMessage() + ")";
            }
            // 兜底:LLM 没调 save_note 时显式保存
            if (tools.notes().isEmpty() || !tools.notes().get(tools.notes().size() - 1).equals(advice)) {
                tools.saveNote(advice);
            }
            sink.next(new StreamEvent("advise", Map.of("advice", advice, "note_saved", true)));
        }

        sink.next(new StreamEvent("done", Map.of("rounds_done", round)));
    }
}
