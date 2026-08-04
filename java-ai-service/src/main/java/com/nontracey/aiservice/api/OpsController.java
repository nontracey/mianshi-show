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

/**
 * 运维接口控制器:/health(健康检查)与 /api/metrics(指标快照)。
 *
 * <p><b>架构位置</b>:api 层,供探活与可观测性。注意本类不加 /api 前缀(health 在根路径),
 * 故类上没有 {@code @RequestMapping}。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/api/ops.py}。
 */
@RestController
public class OpsController {

    private final AppProperties props;
    private final Loader loader;
    private final Metrics metrics;
    /** 注入但当前 health 未实际探活 LLM(llmReachable 固定 true),预留后续真实连通性检查。 */
    private final ChatClient chatClient;

    public OpsController(AppProperties props, Loader loader, Metrics metrics, ChatClient chatClient) {
        this.props = props;
        this.loader = loader;
        this.metrics = metrics;
        this.chatClient = chatClient;
    }

    /**
     * 健康检查端点。
     *
     * <p><b>HTTP</b>:{@code GET /health}。响应 {@link Dtos.HealthData}
     * (状态/版本/模型/向量库/知识库来源/LLM 可达/向量库就绪)。
     *
     * <p>kbSource 按 Loader 同样的优先级选出实际生效的知识库来源用于展示;
     * vectorStoreReady 以当前租户 Loader 中是否有 topic 判断。
     *
     * @return 健康信息
     */
    @GetMapping("/health")
    public ApiResponse<Dtos.HealthData> health() {
        // 按 contentPath > contentUrl > samplePath 的优先级选出展示用的知识库来源
        String kbSource = !props.kb().contentPath().isBlank() ? props.kb().contentPath()
                : !props.kb().contentUrl().isBlank() ? props.kb().contentUrl() : props.kb().samplePath();
        Dtos.HealthData data = new Dtos.HealthData(
                "ok", "0.1.0",
                // 模型名优先取系统属性 app.llm.model,缺省 gpt-4o-mini
                System.getProperty("app.llm.model", "gpt-4o-mini"),
                props.vectorStore(),
                kbSource,
                true,               // llmReachable:当前未真正探活,固定 true(预留)
                loader.count() > 0  // vectorStoreReady:以知识库是否已加载为准
        );
        return ApiResponse.ok(data, TraceIdFilter.current());
    }

    /**
     * 指标快照端点。
     *
     * <p><b>HTTP</b>:{@code GET /api/metrics}。响应 {@link Dtos.MetricsData}
     * (请求数/token/缓存命中率/平均耗时/LLM 调用数)。
     *
     * @return 指标快照
     */
    @GetMapping("/api/metrics")
    public ApiResponse<Dtos.MetricsData> metrics() {
        return ApiResponse.ok(metrics.snapshot(), TraceIdFilter.current());
    }
}
