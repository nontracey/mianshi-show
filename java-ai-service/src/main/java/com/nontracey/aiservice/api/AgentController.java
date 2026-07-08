package com.nontracey.aiservice.api;

import com.nontracey.aiservice.agent.AgentOrchestrator;
import com.nontracey.aiservice.common.ApiResponse;
import com.nontracey.aiservice.common.TraceIdFilter;
import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.dto.StreamEvent;
import com.nontracey.aiservice.infra.Metrics;
import com.nontracey.aiservice.rag.VectorStoreService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** Agent 接口:/api/agent/session(SSE 流式模拟面试)。 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final VectorStoreService vectorStore;
    private final Metrics metrics;

    public AgentController(AgentOrchestrator orchestrator, VectorStoreService vectorStore, Metrics metrics) {
        this.orchestrator = orchestrator;
        this.vectorStore = vectorStore;
        this.metrics = metrics;
    }

    @PostMapping("/agent/session")
    public Object agentSession(@RequestBody Dtos.AgentSessionReq req) {
        long t0 = System.currentTimeMillis();
        if (vectorStore.count() == 0) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(400, "向量库为空,请先 POST /api/ingest", TraceIdFilter.current());
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        new Thread(() -> {
            try {
                for (StreamEvent ev : orchestrator.run(req.topic(), req.rounds())) {
                    try {
                        emitter.send(SseEmitter.event().name(ev.type()).data(ev.payload()));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                        return;
                    }
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("{\"error\":\"" + e.getMessage() + "\"}"));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            } finally {
                metrics.recordRequest(System.currentTimeMillis() - t0);
            }
        }).start();
        return emitter;
    }
}
