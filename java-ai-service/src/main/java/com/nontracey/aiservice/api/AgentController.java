package com.nontracey.aiservice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nontracey.aiservice.agent.AgentOrchestrator;
import com.nontracey.aiservice.common.TraceIdFilter;
import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.dto.StreamEvent;
import com.nontracey.aiservice.infra.Metrics;
import com.nontracey.aiservice.rag.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Agent 接口控制器:/api/agent/session(WebFlux SSE 流式模拟面试)。
 *
 * <p><b>架构位置</b>:api 层。这是全应用唯一的响应式(WebFlux)端点,其余端点都是 Spring MVC;
 * Spring Boot 3 支持两者共存。用 {@link Flux}&lt;{@link ServerSentEvent}&lt;String&gt;&gt; 真流式推送事件,
 * 对齐 B 的 sse-starlette 与 D 的 IAsyncEnumerable(三语言同契约,客户端无感)。
 *
 * <p><b>为什么用 WebFlux</b>:模拟面试是长连接、逐事件下发,响应式流比 MVC 阻塞等待更合适;
 * 而编排逻辑本身是阻塞的,由 AgentOrchestrator 在独立线程里执行并通过 Flux 桥接(见该类注释)。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/api/agent.py}。
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;
    private final VectorStoreService vectorStore;
    private final Metrics metrics;
    /** 把 StreamEvent 的 payload 序列化为 JSON 字符串(SSE data 字段)。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentController(AgentOrchestrator orchestrator, VectorStoreService vectorStore, Metrics metrics) {
        this.orchestrator = orchestrator;
        this.vectorStore = vectorStore;
        this.metrics = metrics;
    }

    /**
     * 模拟面试会话端点(SSE)。
     *
     * <p><b>HTTP</b>:{@code POST /api/agent/session},{@code produces = text/event-stream}。
     * 请求体 {@link Dtos.AgentSessionReq}(topic/rounds);响应为 SSE 事件流,
     * 每帧 {@code event: <type>} + {@code data: <payload JSON>}。
     *
     * <p><b>流程</b>:向量库空检查(空则单帧 error) -> 编排器产出 StreamEvent 流
     * -> 逐帧转成 ServerSentEvent -> 末尾追加 {@code data: [DONE]} 哨兵帧(与 B/D 一致,客户端据此判断结束)
     * -> doFinally 无论成功/失败/取消都记录一次请求耗时。
     *
     * @param req 会话请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/agent/session", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentSession(@RequestBody Dtos.AgentSessionReq req) {
        long t0 = System.currentTimeMillis();
        // 向量库为空时无法检索,直接回一帧 error(仍走 SSE,保持协议一致)
        if (vectorStore.count() == 0) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return Flux.just(sse("error", "{\"error\":\"向量库为空,请先 POST /api/ingest\"}"));
        }
        return orchestrator.runFlux(req.topic(), req.rounds())
                .map(ev -> sse(ev.type(), toJson(ev.payload())))
                // 结束哨兵帧,客户端读到 [DONE] 即知流结束(对齐 B/D)
                .concatWith(Flux.just(sse("done", "[DONE]")))
                // 无论 complete/error/cancel 都埋点记录耗时
                .doFinally(sig -> metrics.recordRequest(System.currentTimeMillis() - t0));
    }

    /** 构造一个 SSE 帧(event 字段 + data 字段)。 */
    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    /** 把事件 payload 序列化为 JSON;失败时返回带 error 的 JSON 字符串而非抛异常,保证流不中断。 */
    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed:" + e.getMessage() + "\"}";
        }
    }
}
