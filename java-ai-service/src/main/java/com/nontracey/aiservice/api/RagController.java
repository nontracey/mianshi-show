package com.nontracey.aiservice.api;

import com.nontracey.aiservice.common.ApiResponse;
import com.nontracey.aiservice.common.TraceIdFilter;
import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.infra.Guardrails;
import com.nontracey.aiservice.infra.Metrics;
import com.nontracey.aiservice.infra.SemanticCache;
import com.nontracey.aiservice.rag.Generator;
import com.nontracey.aiservice.rag.HybridRetriever;
import com.nontracey.aiservice.rag.Loader;
import com.nontracey.aiservice.rag.Splitter;
import com.nontracey.aiservice.rag.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 接口控制器:/api/ingest(知识库入库)与 /api/ask(检索增强问答)。
 *
 * <p><b>架构位置</b>:api 层。把 rag 模块(Loader/Splitter/VectorStoreService/HybridRetriever/Generator)
 * 与 infra(Guardrails/SemanticCache/Metrics)串成对外 HTTP 能力。
 *
 * <p><b>两种 ask 路径</b>(由 mode 参数决定):
 * <ul>
 *   <li>{@code advisor}:走 Spring AI QuestionAnswerAdvisor(纯向量,展示原生能力)。</li>
 *   <li>{@code hybrid}/{@code hybrid_rerank}(默认 hybrid):走 HybridRetriever 混合检索 + 手拼 context。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/api/rag.py} 与 D 项目同名 Controller。
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final Loader loader;
    private final Splitter splitter;
    private final VectorStoreService vectorStore;
    private final HybridRetriever retriever;
    private final Generator generator;
    private final Guardrails guardrails;
    private final Metrics metrics;
    private final SemanticCache cache;

    public RagController(Loader loader, Splitter splitter, VectorStoreService vectorStore,
                         HybridRetriever retriever, Generator generator, Guardrails guardrails,
                         Metrics metrics, SemanticCache cache) {
        this.loader = loader;
        this.splitter = splitter;
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        this.generator = generator;
        this.guardrails = guardrails;
        this.metrics = metrics;
        this.cache = cache;
    }

    /**
     * 知识库入库端点。
     *
     * <p><b>HTTP</b>:{@code POST /api/ingest}。请求体 {@link Dtos.IngestReq}(可选 source);
     * 响应 {@link Dtos.IngestData}(topic 数 / chunk 数 / 内容版本)。
     *
     * <p><b>流程</b>:Loader 加载 -> Splitter 切块 -> 清空并重建向量库 -> 重建 BM25 索引。
     * 向量库与 BM25 都全量重建,保证与知识库一致。
     *
     * @param req 入库请求
     * @return 入库统计;失败返回业务码 500
     */
    @PostMapping("/ingest")
    public ApiResponse<Dtos.IngestData> ingest(@RequestBody Dtos.IngestReq req) {
        long t0 = System.currentTimeMillis();
        try {
            // 1. 加载知识库(三层降级),返回 production topic 数
            int count = loader.load(req.source());
            // 2. 把当前租户的全部 topic 切成 chunk
            var chunks = splitter.splitAll(loader.list());
            // 3. 先清空当前租户向量库,再全量写入,避免新旧混杂
            vectorStore.reset();
            vectorStore.addChunks(chunks);
            // 4. 同步重建 BM25 索引(按租户)
            retriever.rebuildBm25(chunks);
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.ok(new Dtos.IngestData(count, chunks.size(), loader.contentVersion()), TraceIdFilter.current());
        } catch (Exception e) {
            log.error("ingest failed", e);
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(500, "入库失败:" + e.getMessage(), TraceIdFilter.current());
        }
    }

    /**
     * RAG 提问端点。
     *
     * <p><b>HTTP</b>:{@code POST /api/ask?mode=...}。请求体 {@link Dtos.AskReq}(question/topK);
     * 响应 {@link Dtos.AskData}(answer/sources/usage)。
     *
     * <p><b>处理链</b>:护栏注入检测 -> 向量库空检查 -> 语义缓存命中则直接返回 ->
     * 未命中按 mode 走 advisor 或 hybrid 检索 + 生成 -> 回写缓存。
     *
     * @param req  提问请求
     * @param mode 检索模式:advisor / vector / hybrid / hybrid_rerank,默认 hybrid
     * @return 答案与来源;被护栏拦截或向量库为空返回业务码 400
     */
    @PostMapping("/ask")
    public ApiResponse<Dtos.AskData> ask(@RequestBody Dtos.AskReq req,
                                     @RequestParam(defaultValue = "hybrid") String mode) {
        long t0 = System.currentTimeMillis();
        // guardrails:先做 prompt 注入检测,拦下可疑输入
        var g = guardrails.checkInjection(req.question());
        if (g.blocked()) {
            log.warn("输入被拦截:{} | q={}", g.reason(), guardrails.redactPii(req.question()));
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(400, "输入被拒:" + g.reason(), TraceIdFilter.current());
        }
        // 向量库为空时无法检索,提示先 ingest
        if (vectorStore.count() == 0) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(400, "向量库为空,请先 POST /api/ingest", TraceIdFilter.current());
        }
        // 语义缓存:question 向量化 -> 命中相似历史问则直接返回,省一次 LLM 调用
        float[] qEmb = vectorStore.embed(req.question());
        Dtos.AskData cached = cache.get(qEmb);
        if (cached != null) {
            metrics.recordCache(true);
            metrics.recordRequest(System.currentTimeMillis() - t0);
            // 命中:复用历史答案,并在 usage 标注 cache_hit=true
            var hit = new Dtos.AskData(cached.answer(), cached.sources(), java.util.Map.of("cache_hit", true));
            return ApiResponse.ok(hit, TraceIdFilter.current());
        }
        metrics.recordCache(false);
        int topK = req.topK() != null ? req.topK() : 4;
        Dtos.AskData data;
        if ("advisor".equals(mode)) {
            // SpringAI QuestionAnswerAdvisor 路径:Advisor 自动检索 + 注入上下文(纯向量)
            data = generator.generateWithAdvisor(req.question(), topK);
        } else {
            // 混合检索路径:HybridRetriever(向量+BM25+RRF)+ 手拼 context
            var docs = retriever.retrieve(req.question(), topK, mode);
            data = generator.generate(req.question(), docs);
        }
        // 回写语义缓存,供后续相似问题命中
        cache.put(qEmb, data);
        metrics.recordRequest(System.currentTimeMillis() - t0);
        return ApiResponse.ok(data, TraceIdFilter.current());
    }
}
