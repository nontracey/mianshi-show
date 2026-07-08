package com.nontracey.aiservice.api;

import com.nontracey.aiservice.common.ApiResponse;
import com.nontracey.aiservice.common.TraceIdFilter;
import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.config.AppProperties;
import com.nontracey.aiservice.infra.Metrics;
import com.nontracey.aiservice.rag.Loader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维接口:/health 与 /api/metrics。 */
@RestController
public class OpsController {

    private final AppProperties props;
    private final Loader loader;
    private final Metrics metrics;
    private final ChatClient chatClient;

    public OpsController(AppProperties props, Loader loader, Metrics metrics, ChatClient chatClient) {
        this.props = props;
        this.loader = loader;
        this.metrics = metrics;
        this.chatClient = chatClient;
    }

    @GetMapping("/health")
    public ApiResponse<Dtos.HealthData> health() {
        String kbSource = !props.kb().contentPath().isBlank() ? props.kb().contentPath()
                : !props.kb().contentUrl().isBlank() ? props.kb().contentUrl() : props.kb().samplePath();
        Dtos.HealthData data = new Dtos.HealthData(
                "ok", "0.1.0",
                System.getProperty("app.llm.model", "gpt-4o-mini"),
                props.vectorStore(),
                kbSource,
                true,
                loader.count() > 0
        );
        return ApiResponse.ok(data, TraceIdFilter.current());
    }

    @GetMapping("/api/metrics")
    public ApiResponse<Dtos.MetricsData> metrics() {
        return ApiResponse.ok(metrics.snapshot(), TraceIdFilter.current());
    }
}
