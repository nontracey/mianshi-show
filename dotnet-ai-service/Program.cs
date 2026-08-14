// =============================================================================
// Program.cs —— 应用入口(minimal API 风格,无 Startup 类)
//
// 架构位置:对应 B 项目 app/main.py + app/api/*.py、
// C 项目 AiServiceApplication.java + api/*Controller.java。
// 本文件承担四件事:
//   1) 配置绑定:appsettings.json 的 "App" 节 → AppOptions;
//   2) DI 注册:Semantic Kernel、LlmClient、KnowledgeBase、RagService、
//      InterviewService、AgentPlugin、AgentService、Metrics、SemanticCache(全部 singleton,
//      内存态数据跨请求共享;KnowledgeBase 额外走 AddHttpClient 注入 HttpClient);
//   3) 中间件管道:TraceId → Tenant → RateLimit(顺序即执行顺序,
//      限流依赖 Tenant,所以 Tenant 必须在 RateLimit 之前);
//   4) 端点注册:7 个端点,与 B/C 的路径/方法/契约完全一致:
//      GET  /health                 健康检查(轻量,不做重活)
//      POST /api/ingest             知识库加载 + 切块向量化入库
//      POST /api/ask                RAG 问答(护栏→语义缓存→检索→生成,支持 SSE 流式)
//      POST /api/interview/question 出题(基于知识库预置题)
//      POST /api/interview/evaluate LLM-as-Judge 评估回答
//      GET  /api/metrics            运行指标快照
//      POST /api/agent/session      Agent 模拟面试全流程(状态机编排)
//
// 所有响应统一走 ApiResponse 封套 + JsonOptions.Default 序列化,
// traceId 取自 TraceIdMiddleware.CurrentTraceId(与响应头 X-Trace-Id 一致)。
// =============================================================================
using System.ClientModel;
using System.Diagnostics;
using System.Text.Json;
using Microsoft.Extensions.Options;
using Microsoft.SemanticKernel;
using Microsoft.SemanticKernel.Connectors.OpenAI;
using OpenAI;
using DotnetAiService.Common;
using DotnetAiService.Services;

var builder = WebApplication.CreateBuilder(args);

// 注册配置
builder.Services.Configure<AppOptions>(builder.Configuration.GetSection("App"));
// 让 AppOptions 可直接注入(minimal API 端点直接用 AppOptions 参数)。
// 否则只注册了 IOptions<AppOptions>,GET 端点注入 AppOptions 会被当成请求体 → 运行时抛
// "Body was inferred but the method does not allow inferred body parameters"。
builder.Services.AddSingleton(sp => sp.GetRequiredService<IOptions<AppOptions>>().Value);

// Semantic Kernel singleton:ChatCompletion + Embedding(共用 OpenAIClient,支持自定义 endpoint)
// OpenAIClient 指向 BaseUrl(通义/DeepSeek/OpenAI 等兼容端点),Chat 与 Embedding 复用同一客户端;
// Kernel 是 LlmClient(普通对话)与 AgentService(Function Calling)的共同执行器。
builder.Services.AddSingleton<Kernel>(sp =>
{
    var opts = sp.GetRequiredService<AppOptions>();
    var openAIClient = new OpenAIClient(
        new ApiKeyCredential(opts.OpenAI.ApiKey),
        new OpenAIClientOptions { Endpoint = new Uri(opts.OpenAI.BaseUrl.TrimEnd('/')) });
    var kb = Kernel.CreateBuilder();
    kb.AddOpenAIChatCompletion(opts.OpenAI.ChatModel, openAIClient);
#pragma warning disable SKEXP0010
    kb.AddOpenAIEmbeddingGenerator(opts.OpenAI.EmbeddingModel, openAIClient);
#pragma warning restore SKEXP0010
    return kb.Build();
});

builder.Services.AddSingleton<LlmClient>();  // 注入 Kernel + AppOptions(SK 内部管理 HttpClient)
builder.Services.AddHttpClient<KnowledgeBase>();  // KB 拉远程 manifest 需要 HttpClient
builder.Services.AddSingleton<KnowledgeBase>();
builder.Services.AddSingleton<RagService>();
builder.Services.AddSingleton<InterviewService>();
builder.Services.AddSingleton<AgentPlugin>();  // [KernelFunction] 工具,供 AgentService 用
builder.Services.AddSingleton<AgentService>();
builder.Services.AddSingleton<Metrics>();
builder.Services.AddSingleton<SemanticCache>();
builder.Services.AddSingleton<IVectorRepository>(sp =>
{
    var options = sp.GetRequiredService<AppOptions>();
    return options.VectorStore.Equals("pgvector", StringComparison.OrdinalIgnoreCase)
        ? new PgVectorRepository(options.PgVectorConnectionString, options.EmbeddingDimensions)
        : new MemoryVectorRepository();
});
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

var app = builder.Build();

// 中间件管道(按注册顺序执行):
// TraceId 最先(后续所有响应封套/429 都要用 traceId)→ Tenant(解析 X-Tenant-Id)→ 限流(按租户计数)
app.UseMiddleware<TraceIdMiddleware>();
app.UseMiddleware<TenantMiddleware>();      // X-Tenant-Id 头 -> TenantContext
app.UseMiddleware<RateLimitMiddleware>();   // 每租户限流
app.UseSwagger();
app.UseSwaggerUI(o => o.RoutePrefix = "docs");  // /docs 看接口(与 C 对齐)

// ---------- /health ----------
// GET /health —— 健康检查:返回版本、模型、KB 数据源与就绪状态。供探活/面板展示。
app.MapGet("/health", (KnowledgeBase kb, AppOptions opts) =>
{
    // 健康检查必须廉价:不在这里加载 KB(曾误加 LoadAsync→每次拉 429 个远程 topic 而超时)。
    // KB 加载走 /api/ingest;这里只报当前状态。
    return Results.Json(ApiResponse<object>.Ok(new
    {
        status = "ok",
        version = "0.1.0",
        llm_model = opts.OpenAI.ChatModel,
        vector_store = opts.VectorStore,
        // kb_source:按实际生效的数据源展示(本地路径 > 远程 URL > 样例文件)
        kb_source = !string.IsNullOrEmpty(opts.Kb.ContentPath) ? opts.Kb.ContentPath
                   : !string.IsNullOrEmpty(opts.Kb.ContentUrl) ? opts.Kb.ContentUrl : opts.Kb.SamplePath,
        llm_reachable = !string.IsNullOrEmpty(opts.OpenAI.ApiKey),
        vector_store_ready = kb.Count > 0,
    }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
});

// ---------- /api/ingest ----------
// POST /api/ingest —— 知识入库:加载当前租户的知识库(KnowledgeBase)+ 切块向量化(RagService)。
// 必须在使用 /api/ask、/api/agent/session 之前调用一次(它们都校验向量库非空)。
app.MapPost("/api/ingest", async (RagService rag, KnowledgeBase kb, Metrics metrics) =>
{
    var t0 = Stopwatch.GetTimestamp();
    try
    {
        await kb.LoadAsync(null);
        var (topics, chunks) = await rag.IngestAsync();
        metrics.RecordRequest((long)Stopwatch.GetElapsedTime(t0).TotalMilliseconds);
        return Results.Json(ApiResponse<object>.Ok(new
        {
            count = topics, chunks, content_version = kb.ContentVersion,
        }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
        metrics.RecordRequest((long)Stopwatch.GetElapsedTime(t0).TotalMilliseconds);
        return Results.Json(ApiResponse<object>.Err(500, "入库失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/ask ----------
// POST /api/ask —— RAG 问答主端点(本项目核心链路):
// 护栏 → 语义缓存 → 检索(mode: vector|hybrid|hybrid_rerank) → 生成(stream=SSE) → 指标
// 请求体 AskReq:{question, top_k?, stream};mode 走 query 参数,缺省 hybrid。
app.MapPost("/api/ask", async (HttpContext ctx, RagService rag, LlmClient llm, SemanticCache cache, Metrics metrics, AskReq req, string? mode) =>
{
    var t0 = Stopwatch.GetTimestamp();
    long Ms() => (long)Stopwatch.GetElapsedTime(t0).TotalMilliseconds;

    // 护栏:提示注入/空输入/超长直接 400,不消耗任何 Embedding/LLM 资源
    var (blocked, reason) = Guardrails.DetectInjection(req.question);
    if (blocked)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(400, "输入被拒:" + reason, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    // 前置条件:未入库则提示先调 /api/ingest(ChunkCount 按当前租户统计)
    if (rag.ChunkCount == 0)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(400, "向量库为空,请先 POST /api/ingest", TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }

    var m = string.IsNullOrEmpty(mode) ? "hybrid" : mode;

    // 先算问题向量:语义缓存判定与后续向量检索共用,避免重复调 Embedding
    float[] qEmb;
    try { qEmb = (await llm.EmbedAsync(new List<string> { req.question }))[0]; }
    catch (Exception e)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(503, "Embedding 调用失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }

    // 语义缓存(非流式才走缓存)
    // 流式请求不查缓存:SSE 打字机效果无法用缓存整体重放;命中则直接返回,省掉检索+生成
    if (!req.stream && cache.Get(qEmb) is { } hit)
    {
        metrics.RecordCache(true);
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Ok(hit, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    metrics.RecordCache(false);

    // 混合检索:按 mode 走 vector / hybrid(RRF 融合) / hybrid_rerank(+LLM 重排)
    var docs = await rag.RetrieveAsync(req.question, req.top_k ?? 4, m);

    if (req.stream)
    {
        // SSE:先发检索来源事件,再逐 token 发 answer,最后 done
        // SSE 协议要点:Content-Type=text/event-stream;每个事件以 "\n\n" 结尾;
        // 每帧写完立即 FlushAsync,否则会被缓冲,前端看不到打字机效果
        ctx.Response.Headers.ContentType = "text/event-stream";
        var sources = RagService.GetSources(docs);
        // 事件一:retrieve —— 先把引用来源推给前端(生成尚未开始,来源可先行展示)
        await ctx.Response.WriteAsync($"event: retrieve\ndata: {JsonSerializer.Serialize(new { mode = m, sources }, JsonOptions.Default)}\n\n");
        await ctx.Response.Body.FlushAsync();
        try
        {
            // 事件流:data 帧逐 token 推送;ctx.RequestAborted 传入迭代器,客户端断开即终止生成
            await foreach (var tok in rag.GenerateStreamAsync(req.question, docs, ctx.RequestAborted))
            {
                await ctx.Response.WriteAsync($"data: {JsonSerializer.Serialize(tok)}\n\n");
                await ctx.Response.Body.FlushAsync();
            }
            // 正常结束标记:前端收到 [DONE] 即关闭连接
            await ctx.Response.WriteAsync("event: done\ndata: [DONE]\n\n");
        }
        catch (Exception e)
        {
            // 流已发出无法改 HTTP 状态码,只能以 error 事件告知前端
            await ctx.Response.WriteAsync($"event: error\ndata: {JsonSerializer.Serialize(e.Message)}\n\n");
        }
        metrics.RecordLlm(0);
        metrics.RecordRequest(Ms());
        return Results.Empty;  // 响应体已手写完毕,不再走 Results.Json
    }

    try
    {
        // 非流式:一次性生成,成功后写入语义缓存供相似问题复用
        var (answer, sources, tokens) = await rag.GenerateAsync(req.question, docs);
        var payload = new { answer, sources, usage = new { total_tokens = tokens } };
        cache.Put(qEmb, payload);
        metrics.RecordLlm(tokens);
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Ok(payload, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(503, "LLM 调用失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/interview/question ----------
// POST /api/interview/question —— 出题:从 topic 的预置 recallPrompts 取题(不调 LLM,零延迟),
// 支持按难度过滤与指定数量。请求体 QuestionReq:{topic, difficulty?, count}。
app.MapPost("/api/interview/question", (InterviewService svc, QuestionReq req) =>
{
    try
    {
        // count 缺省/0 时兜底出 1 题
        var qs = svc.GenerateQuestions(req.topic, req.difficulty, req.count == 0 ? 1 : req.count);
        return Results.Json(ApiResponse<object>.Ok(new { questions = qs }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (ArgumentException e)
    {
        // topic 不存在属"资源不存在"语义 → 404
        return Results.Json(ApiResponse<object>.Err(404, e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/interview/evaluate ----------
// POST /api/interview/evaluate —— LLM-as-Judge 评估:按 topic 预置 rubric 给回答打分,
// 输出 score/hit/missed/mistakes/feedback;LLM 异常时服务端降级(degraded=true)。
// 请求体 EvaluateReq:{question_id, user_answer, stream}(stream 字段保留,D 暂未实现流式评估)。
app.MapPost("/api/interview/evaluate", async (InterviewService svc, EvaluateReq req) =>
{
    try
    {
        var ev = await svc.EvaluateAsync(req.question_id, req.user_answer);
        return Results.Json(ApiResponse<object>.Ok(new { evaluation = ev }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (ArgumentException e)
    {
        // topic 不存在 / 缺 rubric.mustHave → 404
        return Results.Json(ApiResponse<object>.Err(404, e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
        // 其他异常(理论上 EvaluateAsync 内部已降级,这里兜底)→ 503
        return Results.Json(ApiResponse<object>.Err(503, "评估失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/metrics ----------
// GET /api/metrics —— 运行指标快照:请求数/token 总量/LLM 调用数/缓存命中率/平均延迟。
app.MapGet("/api/metrics", (Metrics metrics) =>
    Results.Json(ApiResponse<object>.Ok(metrics.Snapshot(), TraceIdMiddleware.CurrentTraceId), JsonOptions.Default));

// ---------- /api/agent/session ----------
// POST /api/agent/session —— Agent 模拟面试:跑完整状态机
// retrieve → ask → simulate → evaluate → decide(followup?) → advise,一次性返回事件列表。
// 前置条件与 /api/ask 相同:向量库必须已入库。请求体 AgentSessionReq:{topic, rounds}。
app.MapPost("/api/agent/session", async (HttpContext ctx, AgentService agent, RagService rag, AgentSessionReq req) =>
{
    if (rag.ChunkCount == 0)
        return Results.Json(ApiResponse<object>.Err(400, "向量库为空,请先 POST /api/ingest", TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    try
    {
        ctx.Response.ContentType = "text/event-stream";
        await foreach (var evt in agent.RunStreamAsync(
            req.topic, req.rounds == 0 ? 1 : req.rounds, ctx.RequestAborted))
        {
            var type = evt["type"]?.ToString() ?? "message";
            await ctx.Response.WriteAsync(
                $"event: {type}\ndata: {JsonSerializer.Serialize(evt, JsonOptions.Default)}\n\n",
                ctx.RequestAborted);
            await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
        }
        return Results.Empty;
    }
    catch (OperationCanceledException) when (ctx.RequestAborted.IsCancellationRequested)
    {
        return Results.Empty;
    }
    catch (Exception e)
    {
        return Results.Json(ApiResponse<object>.Err(500, "Agent 失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

app.Run();

// ---------- DTO ----------
// 请求体 record:属性名用 snake_case(question/top_k/question_id/user_answer),
// 与 B/C 的 JSON 契约逐字段一致;System.Text.Json 按名称直接绑定。
/// <summary>/api/ask 请求体:问题、返回条数(缺省 4)、是否 SSE 流式。</summary>
public record AskReq(string question, int? top_k, bool stream);
/// <summary>/api/interview/question 请求体:topic id、难度过滤、题目数量。</summary>
public record QuestionReq(string topic, int? difficulty, int count);
/// <summary>/api/interview/evaluate 请求体:题目 id、候选人回答、stream(保留字段)。</summary>
public record EvaluateReq(string question_id, string user_answer, bool stream);
/// <summary>/api/agent/session 请求体:面试主题、期望轮数。</summary>
public record AgentSessionReq(string topic, int rounds);
