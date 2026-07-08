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
// Swagger 待补:装 Swashbuckle 后启用 AddEndpointsApiExplorer + AddSwaggerGen
builder.Services.AddEndpointsApiExplorer();

var app = builder.Build();

app.UseMiddleware<TraceIdMiddleware>();
// Swagger 待补:app.UseSwagger(); app.UseSwaggerUI();

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
app.MapPost("/api/ingest", async (RagService rag, KnowledgeBase kb, HttpRequest req) =>
{
    try
    {
        await kb.LoadAsync(null);
        var (topics, chunks) = await rag.IngestAsync();
        return Results.Json(ApiResponse<object>.Ok(new
        {
            count = topics, chunks, content_version = kb.ContentVersion,
        }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
        return Results.Json(ApiResponse<object>.Err(500, "入库失败:" + e.Message, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
});

// ---------- /api/ask ----------
app.MapPost("/api/ask", async (RagService rag, AskReq req) =>
{
    if (rag.ChunkCount == 0)
        return Results.Json(ApiResponse<object>.Err(400, "向量库为空,请先 POST /api/ingest", TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    try
    {
        var (answer, sources) = await rag.AskAsync(req.question, req.top_k ?? 4, "hybrid");
        return Results.Json(ApiResponse<object>.Ok(new
        {
            answer, sources, usage = new { },
        }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default);
    }
    catch (Exception e)
    {
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
app.MapGet("/api/metrics", () =>
    Results.Json(ApiResponse<object>.Ok(new
    {
        requests_total = 0, tokens_total = 0, cache_hits = 0, cache_misses = 0,
        cache_hit_rate = 0.0, avg_latency_ms = 0.0, llm_calls = 0,
    }, TraceIdMiddleware.CurrentTraceId), JsonOptions.Default));

// ---------- /api/agent/session ----------
app.MapPost("/api/agent/session", async (AgentService agent, AgentSessionReq req) =>
{
    if (((RagService)app.Services.GetRequiredService(typeof(RagService))).ChunkCount == 0)
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
