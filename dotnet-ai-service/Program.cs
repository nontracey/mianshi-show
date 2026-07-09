using System.Diagnostics;
using System.Text.Json;
using Microsoft.Extensions.Options;
using DotnetAiService.Common;
using DotnetAiService.Services;

var builder = WebApplication.CreateBuilder(args);

// 注册配置
builder.Services.Configure<AppOptions>(builder.Configuration.GetSection("App"));
// 让 AppOptions 可直接注入(minimal API 端点直接用 AppOptions 参数)。
// 否则只注册了 IOptions<AppOptions>,GET 端点注入 AppOptions 会被当成请求体 → 运行时抛
// "Body was inferred but the method does not allow inferred body parameters"。
builder.Services.AddSingleton(sp => sp.GetRequiredService<IOptions<AppOptions>>().Value);
builder.Services.AddHttpClient<LlmClient>();
builder.Services.AddSingleton<KnowledgeBase>();
builder.Services.AddSingleton<RagService>();
builder.Services.AddSingleton<InterviewService>();
builder.Services.AddSingleton<AgentService>();
builder.Services.AddSingleton<Metrics>();
builder.Services.AddSingleton<SemanticCache>();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

var app = builder.Build();

app.UseMiddleware<TraceIdMiddleware>();
app.UseSwagger();
app.UseSwaggerUI(o => o.RoutePrefix = "docs");  // /docs 看接口(与 C 对齐)

// ---------- /health ----------
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
        kb_source = !string.IsNullOrEmpty(opts.Kb.ContentPath) ? opts.Kb.ContentPath
                   : !string.IsNullOrEmpty(opts.Kb.ContentUrl) ? opts.Kb.ContentUrl : opts.Kb.SamplePath,
        llm_reachable = !string.IsNullOrEmpty(opts.OpenAI.ApiKey),
        vector_store_ready = kb.Count > 0,
    }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
});

// ---------- /api/ingest ----------
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
// 护栏 → 语义缓存 → 检索(mode: vector|hybrid|hybrid_rerank) → 生成(stream=SSE) → 指标
app.MapPost("/api/ask", async (HttpContext ctx, RagService rag, LlmClient llm, SemanticCache cache, Metrics metrics, AskReq req, string? mode) =>
{
    var t0 = Stopwatch.GetTimestamp();
    long Ms() => (long)Stopwatch.GetElapsedTime(t0).TotalMilliseconds;

    var (blocked, reason) = Guardrails.DetectInjection(req.question);
    if (blocked)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(400, "输入被拒:" + reason, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    if (rag.ChunkCount == 0)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(400, "向量库为空,请先 POST /api/ingest", TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }

    var m = string.IsNullOrEmpty(mode) ? "hybrid" : mode;

    float[] qEmb;
    try { qEmb = (await llm.EmbedAsync(new List<string> { req.question }))[0]; }
    catch (Exception e)
    {
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Err(503, "Embedding 调用失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }

    // 语义缓存(非流式才走缓存)
    if (!req.stream && cache.Get(qEmb) is { } hit)
    {
        metrics.RecordCache(true);
        metrics.RecordRequest(Ms());
        return Results.Json(ApiResponse<object>.Ok(hit, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    metrics.RecordCache(false);

    var docs = await rag.RetrieveAsync(req.question, req.top_k ?? 4, m);

    if (req.stream)
    {
        // SSE:先发检索来源事件,再逐 token 发 answer,最后 done
        ctx.Response.Headers.ContentType = "text/event-stream";
        var sources = RagService.GetSources(docs);
        await ctx.Response.WriteAsync($"event: retrieve\ndata: {JsonSerializer.Serialize(new { mode = m, sources }, JsonOptions.Default)}\n\n");
        await ctx.Response.Body.FlushAsync();
        try
        {
            await foreach (var tok in rag.GenerateStreamAsync(req.question, docs, ctx.RequestAborted))
            {
                await ctx.Response.WriteAsync($"data: {JsonSerializer.Serialize(tok)}\n\n");
                await ctx.Response.Body.FlushAsync();
            }
            await ctx.Response.WriteAsync("event: done\ndata: [DONE]\n\n");
        }
        catch (Exception e)
        {
            await ctx.Response.WriteAsync($"event: error\ndata: {JsonSerializer.Serialize(e.Message)}\n\n");
        }
        metrics.RecordLlm(0);
        metrics.RecordRequest(Ms());
        return Results.Empty;
    }

    try
    {
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
app.MapPost("/api/interview/question", (InterviewService svc, QuestionReq req) =>
{
    try
    {
        var qs = svc.GenerateQuestions(req.topic, req.difficulty, req.count == 0 ? 1 : req.count);
        return Results.Json(ApiResponse<object>.Ok(new { questions = qs }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (ArgumentException e)
    {
        return Results.Json(ApiResponse<object>.Err(404, e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/interview/evaluate ----------
app.MapPost("/api/interview/evaluate", async (InterviewService svc, EvaluateReq req) =>
{
    try
    {
        var ev = await svc.EvaluateAsync(req.question_id, req.user_answer);
        return Results.Json(ApiResponse<object>.Ok(new { evaluation = ev }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (ArgumentException e)
    {
        return Results.Json(ApiResponse<object>.Err(404, e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
        return Results.Json(ApiResponse<object>.Err(503, "评估失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/metrics ----------
app.MapGet("/api/metrics", (Metrics metrics) =>
    Results.Json(ApiResponse<object>.Ok(metrics.Snapshot(), TraceIdMiddleware.CurrentTraceId), JsonOptions.Default));

// ---------- /api/agent/session ----------
app.MapPost("/api/agent/session", async (AgentService agent, RagService rag, AgentSessionReq req) =>
{
    if (rag.ChunkCount == 0)
        return Results.Json(ApiResponse<object>.Err(400, "向量库为空,请先 POST /api/ingest", TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    try
    {
        var events = await agent.RunAsync(req.topic, req.rounds == 0 ? 1 : req.rounds);
        return Results.Json(ApiResponse<object>.Ok(new { events }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
        return Results.Json(ApiResponse<object>.Err(500, "Agent 失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

app.Run();

// ---------- DTO ----------
public record AskReq(string question, int? top_k, bool stream);
public record QuestionReq(string topic, int? difficulty, int count);
public record EvaluateReq(string question_id, string user_answer, bool stream);
public record AgentSessionReq(string topic, int rounds);
