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

/** RAG 接口:/api/ingest 与 /api/ask。 */
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

    @PostMapping("/ingest")
    public ApiResponse<Dtos.IngestData> ingest(@RequestBody Dtos.IngestReq req) {
        long t0 = System.currentTimeMillis();
        try {
            int count = loader.load(req.source());
            var chunks = splitter.splitAll(loader.list());
            vectorStore.reset();
            vectorStore.add(chunks);
            retriever.rebuildBm25(chunks);
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.ok(new Dtos.IngestData(count, chunks.size(), loader.contentVersion()), TraceIdFilter.current());
        } catch (Exception e) {
            log.error("ingest failed", e);
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(500, "入库失败:" + e.getMessage(), TraceIdFilter.current());
        }
    }

    @PostMapping("/ask")
    public ApiResponse<Dtos.AskData> ask(@RequestBody Dtos.AskReq req,
                                     @RequestParam(defaultValue = "hybrid") String mode) {
        long t0 = System.currentTimeMillis();
        // guardrails
        var g = guardrails.checkInjection(req.question());
        if (g.blocked()) {
            log.warn("输入被拦截:{} | q={}", g.reason(), guardrails.redactPii(req.question()));
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(400, "输入被拒:" + g.reason(), TraceIdFilter.current());
        }
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
            var hit = new Dtos.AskData(cached.answer(), cached.sources(), java.util.Map.of("cache_hit", true));
            return ApiResponse.ok(hit, TraceIdFilter.current());
        }
        metrics.recordCache(false);
        int topK = req.topK() != null ? req.topK() : 4;
        var docs = retriever.retrieve(req.question(), topK, mode);
        Dtos.AskData data = generator.generate(req.question(), docs);
        cache.put(qEmb, data);
        metrics.recordRequest(System.currentTimeMillis() - t0);
        return ApiResponse.ok(data, TraceIdFilter.current());
    }
}
