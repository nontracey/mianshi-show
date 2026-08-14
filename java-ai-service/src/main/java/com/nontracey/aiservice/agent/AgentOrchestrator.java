package com.nontracey.aiservice.agent;

import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.dto.Dtos.Evaluation;
import com.nontracey.aiservice.dto.Dtos.Question;
import com.nontracey.aiservice.dto.StreamEvent;
import com.nontracey.aiservice.interview.EvaluatorService;
import com.nontracey.aiservice.interview.QuestionService;
import com.nontracey.aiservice.rag.VectorStoreService.ScoredDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;

/**
 * Agent 状态机编排器(与 B 的 graph.py 对应):一轮模拟面试的完整流程编排。
 *
 * <p><b>架构位置</b>:agent 模块核心。被 AgentController 的 /api/agent/session(SSE)调用。
 *
 * <p><b>状态机节点</b>:retrieve(tool: search_knowledge) -> ask -> simulate -> evaluate
 * -> decide -> followup / advise。其中 decide 按评估分决定是继续追问(followup)还是收尾。
 *
 * <p><b>两种调用 LLM 的方式</b>:
 * <ul>
 *   <li>retrieve / advise 节点用 Spring AI {@link ToolCallback} 把工具暴露给 LLM,由 LLM 通过
 *       Function Calling 自主决定调用(展示 SpringAI 工具机制);因 Function Calling 有不确定性,
 *       两处都带显式兜底(LLM 没调工具时代码直接调)。</li>
 *   <li>ask / simulate / evaluate 走显式编排(流程固定,直接调用对应 Service)。</li>
 * </ul>
 *
 * <p><b>流式输出</b>:{@link #runFlux} 返回 {@link Flux}&lt;{@link StreamEvent}&gt;,供 WebFlux SSE
 * 端点逐事件真流式推送。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/agent/graph.py}(状态图)。
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentTools tools;
    private final QuestionService questionService;
    private final EvaluatorService evaluatorService;
    private final ChatClient chatClient;
    /** search_knowledge 工具的 ToolCallback 封装(retrieve 节点用)。 */
    private final ToolCallback searchCallback;
    /** save_note 工具的 ToolCallback 封装(advise 节点用)。 */
    private final ToolCallback saveCallback;

    /**
     * 构造编排器,并把 AgentTools 的方法包装成 FunctionToolCallback。
     *
     * <p><b>为什么这样注册</b>:使用 Spring AI 1.1.x 的 {@link FunctionToolCallback} 显式声明
     * 工具名、描述(给 LLM 看的)、
     * 实际执行的 lambda、以及 inputType(record)。inputType 用于自动生成 JSON Schema,
     * 让 LLM 知道入参结构。topK 在 lambda 里做 null 兜底(默认 4),避免 LLM 漏传。
     */
    public AgentOrchestrator(AgentTools tools, QuestionService questionService,
                             EvaluatorService evaluatorService, ChatClient chatClient) {
        this.tools = tools;
        this.questionService = questionService;
        this.evaluatorService = evaluatorService;
        this.chatClient = chatClient;
        // 把 AgentTools 方法包成 ToolCallback,LLM 可通过 Function Calling 调用
        this.searchCallback = FunctionToolCallback.builder("search_knowledge",
                        (AgentTools.SearchKnowledgeInput i) -> tools.searchKnowledge(
                                i.query(), i.topK() == null ? 4 : i.topK()))
                .description("检索面试知识库,返回与 query 相关的知识条目")
                .inputType(AgentTools.SearchKnowledgeInput.class)
                .build();
        this.saveCallback = FunctionToolCallback.builder("save_note",
                        (AgentTools.SaveNoteInput i) -> tools.saveNote(i.text()))
                .description("记一条学习笔记到本地")
                .inputType(AgentTools.SaveNoteInput.class)
                .build();
    }

    /**
     * 流式跑一轮模拟面试,逐事件 yield(供 SSE)。
     *
     * <p><b>为什么开独立线程</b>:状态机编排(runInternal)是一连串阻塞式 LLM 调用;而 WebFlux 的
     * Flux 订阅默认跑在 Netty 事件循环上,绝不能在那里阻塞(会拖垮所有连接)。因此用
     * {@code Flux.create} 作为桥接:在单独的 worker 线程里执行阻塞编排,每产出一个事件就
     * {@code sink.next(...)} 推进响应式流,实现"命令式阻塞代码 -> 响应式流"的转换。
     * worker 设为 daemon,避免会话线程阻止 JVM 退出。
     *
     * @param topic  面试主题
     * @param rounds 期望轮数(小于 1 按 1 处理)
     * @return 事件流;编排正常结束则 complete,异常则 error
     */
    public Flux<StreamEvent> runFlux(String topic, int rounds) {
        int actualRounds = Math.max(1, rounds);
        String tenant = TenantContext.get();
        return Flux.create(sink -> {
            // 独立线程承载阻塞编排,事件经 sink 逐个推入响应式流
            Thread worker = new Thread(() -> TenantContext.runAs(tenant, () -> {
                try {
                    runInternal(topic, actualRounds, sink);
                    sink.complete();
                } catch (Exception e) {
                    sink.error(e);
                }
            }), "agent-orchestrator");
            worker.setDaemon(true);
            sink.onCancel(worker::interrupt);
            sink.onDispose(worker::interrupt);
            worker.start();
        });
    }

    /**
     * 状态机主体:在 worker 线程中顺序执行各节点,逐事件经 sink 推送。
     *
     * <p>节点顺序:retrieve -> [ask -> simulate -> evaluate -> decide]* -> advise -> done。
     * 每个节点完成后立即 {@code sink.next} 一个事件,SSE 端即可实时下发。
     *
     * @param topic  面试主题
     * @param rounds 问答轮数
     * @param sink   事件下发器
     */
    private void runInternal(String topic, int rounds, FluxSink<StreamEvent> sink) {
        // 1. retrieve(LLM 调 search_knowledge 工具)
        // 先清掉上一次的旁路结果,避免线程复用取到旧数据
        AgentTools.clearLastRetrieved();
        List<ScoredDoc> docs;
        try {
            // 让 LLM 以 Function Calling 方式自主调用 search_knowledge(.functions 注入工具)
            chatClient.prompt()
                    .system(s -> s.text("你是技术面试官。请调用 search_knowledge 工具检索 topic:" + topic
                            + " 的知识(query=" + topic + ", topK=4),了解重点后再出题。"))
                    .user("开始检索。")
                    .toolCallbacks(searchCallback)
                    .call()
                    .content();
            // 工具返回值只回给 LLM,编排器从 ThreadLocal 旁路取回真正的 docs
            docs = AgentTools.lastRetrieved();
        } catch (Exception e) {
            log.warn("retrieve Function Calling 失败,降级显式检索:{}", e.getMessage());
            docs = null;
        }
        if (docs == null || docs.isEmpty()) {
            // LLM 没调工具或调用失败,fallback 显式检索(Function Calling 有不确定性,必须兜底)
            docs = tools.searchKnowledge(topic, 4);
        }
        final List<ScoredDoc> docsFinal = docs;
        // 下发 retrieve 事件:只带前 3 条摘要,避免 SSE 帧过大
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
        // 多轮问答循环:每轮 ask -> simulate -> evaluate -> decide
        while (round < rounds) {
            if (sink.isCancelled() || Thread.currentThread().isInterrupted()) return;
            round++;
            // 2. ask:为该 topic 出一道题(难度不过滤,取第一道)
            Dtos.QuestionData qd = questionService.generate(topic, null, 1);
            if (qd.questions().isEmpty()) {
                // 无可出题目:下发 error 事件并终止整个会话
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
                // 让 LLM 扮演候选人作答;允许有遗漏但不能编造,模拟真实水平供评估
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

            // 4. evaluate:LLM-as-Judge 按 rubric 打分
            try {
                lastEval = evaluatorService.evaluate(q.questionId(), answer);
            } catch (Exception e) {
                // 评估失败:下发 error 事件并终止
                sink.next(new StreamEvent("error", Map.of("msg", "评估失败:" + e.getMessage())));
                return;
            }
            sink.next(new StreamEvent("evaluate", Map.of(
                    "score", lastEval.score(), "missed", lastEval.missed(),
                    "mistakes", lastEval.mistakes(), "feedback", lastEval.feedback(),
                    "degraded", lastEval.degraded())));

            // 5. decide:分数低于 70 且还有剩余轮次 -> 继续追问(followup);否则跳出进入 advise
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
                // 把评估结果(score/missed/mistakes)注入 prompt,要求给针对性建议并调 save_note 保存
                String systemText = "你是面试教练。基于评估给 3 条学习建议,补足 missed。\n"
                        + "评估:score=" + evalRef.score() + ", missed=" + evalRef.missed()
                        + ", mistakes=" + evalRef.mistakes()
                        + "\n给完建议后,调用 save_note 工具把建议原文保存(text=建议全文)。";
                advice = chatClient.prompt()
                        .system(systemText)
                        .user("请给建议并保存。")
                        .toolCallbacks(saveCallback)
                        .call()
                        .content();
                if (advice == null) advice = "";
            } catch (Exception e) {
                advice = "(建议生成失败:" + e.getMessage() + ")";
            }
            // 兜底:LLM 没调 save_note 时显式保存。判断依据:笔记为空,或最后一条笔记与建议文本不一致
            if (tools.notes().isEmpty() || !tools.notes().get(tools.notes().size() - 1).equals(advice)) {
                tools.saveNote(advice);
            }
            sink.next(new StreamEvent("advise", Map.of("advice", advice, "note_saved", true)));
        }

        // 会话结束事件,带上实际完成的轮数
        sink.next(new StreamEvent("done", Map.of("rounds_done", round)));
    }
}
