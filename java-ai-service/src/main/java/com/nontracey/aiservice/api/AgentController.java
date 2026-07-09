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

/** Agent 接口:/api/agent/session(WebFlux SSE 流式模拟面试)。
 * <p>用 {@link Flux}&lt;{@link ServerSentEvent}&lt;String&gt;&gt; 真流式推送事件,
 * 对齐 B 的 sse-starlette 与 D 的 IAsyncEnumerable(三语言同契约)。 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;
    private final VectorStoreService vectorStore;
    private final Metrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentController(AgentOrchestrator orchestrator, VectorStoreService vectorStore, Metrics metrics) {
        this.orchestrator = orchestrator;
        this.vectorStore = vectorStore;
        this.metrics = metrics;
    }

    @PostMapping(value = "/agent/session", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentSession(@RequestBody Dtos.AgentSessionReq req) {
        long t0 = System.currentTimeMillis();
        if (vectorStore.count() == 0) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return Flux.just(sse("error", "{\"error\":\"向量库为空,请先 POST /api/ingest\"}"));
        }
        return orchestrator.runFlux(req.topic(), req.rounds())
                .map(ev -> sse(ev.type(), toJson(ev.payload())))
                .concatWith(Flux.just(sse("done", "[DONE]")))
                .doFinally(sig -> metrics.recordRequest(System.currentTimeMillis() - t0));
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed:" + e.getMessage() + "\"}";
        }
    }
}
