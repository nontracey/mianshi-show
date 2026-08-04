using System.Runtime.CompilerServices;
using System.Text.Json;
using DotnetAiService.Common;
using Microsoft.SemanticKernel;
using Microsoft.SemanticKernel.ChatCompletion;
using Microsoft.SemanticKernel.Connectors.OpenAI;
using Microsoft.SemanticKernel.Embeddings;
using OpenAI;

namespace DotnetAiService.Services;

/// <summary>基于 Semantic Kernel 的 LLM 客户端(替代原 HttpClient 直调)。
/// Kernel 由 DI 注入(Program.cs 注册 singleton,复用 OpenAIClient),
/// 支持 OpenAI 兼容 endpoint(通义/DeepSeek/OpenAI/本地模型)。
/// 评估用 OpenAIPromptExecutionSettings{Temperature=0} 保证可复现;
/// JSON 输出走 ResponseFormat=ChatResponseFormat.JsonObject。
/// <para>架构位置:全项目唯一与模型交互的出口(RAG 生成、评估、模拟回答、重排、Agent 提示词
/// 都经由本类),对应 B 项目 app/infra/llm.py;C 项目没有等价封装,直接在服务里用 Spring AI。
/// 好处:换模型供应商只改 Program.cs 的 Kernel 注册,业务代码不感知。</para>
/// <para>关键设计点:不直接用 OpenAI SDK,而是通过 SK 的 IChatCompletionService /
/// ITextEmbeddingGenerationService 抽象 —— SK 统一管理 Chat/Embedding/Plugin,
/// AgentService 的 Function Calling 与这里的普通对话共用同一个 Kernel 实例。</para>
/// <para>接口风格:message 列表统一用 List&lt;Dictionary&lt;string,string&gt;&gt;({role, content}),
/// 与 B/C 的消息结构同构,方便三语言对照阅读。</para>
/// </summary>
public class LlmClient
{
    private readonly Kernel _kernel;
    /// <summary>对话服务(SK 抽象,底层是 Program.cs 注册的 OpenAI Chat Completion)。</summary>
    private readonly IChatCompletionService _chat;
    /// <summary>向量服务(SK 抽象,底层是 Program.cs 注册的 OpenAI Embedding)。</summary>
    private readonly ITextEmbeddingGenerationService _embed;
    private readonly AppOptions _opts;

    /// <summary>构造:从注入的 Kernel 解析出 Chat/Embedding 两个服务句柄。
    /// GetRequiredService 失败即抛异常 —— 配置错误应在启动时暴露,不要拖到运行时。</summary>
    public LlmClient(Kernel kernel, AppOptions opts)
    {
        _kernel = kernel;
        _opts = opts;
        _chat = kernel.GetRequiredService<IChatCompletionService>();
        _embed = kernel.GetRequiredService<ITextEmbeddingGenerationService>();
    }

    /** 暴露 Kernel 供 AgentService 注册插件/InvokePromptAsync 用。 */
    public Kernel Kernel => _kernel;

    /// <summary>当前配置的对话模型名(/health 展示用)。</summary>
    public string ChatModel => _opts.OpenAI.ChatModel;

    /// <summary>一次性对话(非流式)。返回 (回复正文, token 用量)。
    /// 用量从响应 Metadata 的 OpenAI ChatTokenUsage 提取,供 Metrics 统计与响应 usage 字段;
    /// 部分兼容端点不返回用量时为空字典,调用方自行兜底。</summary>
    /// <param name="messages">[{role, content}, ...] 消息列表(system/user/assistant)。</param>
    /// <param name="temperature">采样温度:评估/重排用 0(可复现),生成用 0.3,模拟回答用 0.5。</param>
    /// <returns>(回复正文, usage 字典:prompt_tokens/completion_tokens/total_tokens)。</returns>
    /// <exception cref="Exception">网络/鉴权/模型错误原样上抛,由端点转 503/500。</exception>
    public async Task<(string content, Dictionary<string, object?> usage)> ChatAsync(
        List<Dictionary<string, string>> messages, double temperature = 0.0)
    {
        var history = ToChatHistory(messages);
        var settings = new OpenAIPromptExecutionSettings { Temperature = temperature };
        var resp = await _chat.GetChatMessageContentAsync(history, settings);
        return (resp.Content ?? "", ExtractUsage(resp));
    }

    /// <summary>批量文本向量化(入库切块、提问、语义缓存共用)。
    /// 空列表直接返回空结果,避免无谓的 API 调用。</summary>
    /// <param name="texts">待向量化文本列表(一次请求批量发送,RagService 按 64/批)。</param>
    /// <returns>与输入顺序一致的向量列表。</returns>
    public async Task<List<float[]>> EmbedAsync(List<string> texts)
    {
        if (texts.Count == 0) return new();
        var embs = await _embed.GenerateEmbeddingsAsync(texts);
        return embs.Select(e => e.ToArray()).ToList();
    }

    /// <summary>要求模型输出 JSON 的对话变体(评估、重排用)。</summary>
    /// <returns>模型回复原文(期望是合法 JSON,解析责任在调用方,解析失败各自降级)。</returns>
    public async Task<string> ChatJsonAsync(List<Dictionary<string, string>> messages, double temperature = 0.0)
    {
        // OpenAI SDK 2.x 的 ResponseFormat API 在 SK 1.78 下不稳定,改靠 prompt 约束 JSON
        // (调用方 InterviewService 的 system prompt 已强制"输出严格 JSON")
        var (content, _) = await ChatAsync(messages, temperature);
        return content;
    }

    /// <summary>流式 chat:逐块 yield content(供 SSE 转发)。</summary>
    /// <param name="messages">消息列表。</param>
    /// <param name="temperature">采样温度。</param>
    /// <param name="ct">取消令牌;[EnumeratorCancellation] 使端点的 ctx.RequestAborted
    /// 能传入迭代器 —— 客户端断开 SSE 时及时终止底层流式请求,不浪费 token。</param>
    public async IAsyncEnumerable<string> ChatStreamAsync(
        List<Dictionary<string, string>> messages, double temperature = 0.3,
        [EnumeratorCancellation] CancellationToken ct = default)
    {
        var history = ToChatHistory(messages);
        var settings = new OpenAIPromptExecutionSettings { Temperature = temperature };
        await foreach (var chunk in _chat.GetStreamingChatMessageContentsAsync(history, settings, cancellationToken: ct))
        {
            // 过滤空块(部分供应商首发 role 块无正文),只转发有内容的增量
            if (!string.IsNullOrEmpty(chunk.Content)) yield return chunk.Content;
        }
    }

    /// <summary>消息字典列表 → SK ChatHistory 的适配层:
    /// role 字符串(system/assistant/其他→user)映射到 AuthorRole 枚举。
    /// 统一走这里,保证全项目消息格式一致。</summary>
    private static ChatHistory ToChatHistory(List<Dictionary<string, string>> messages)
    {
        var history = new ChatHistory();
        foreach (var m in messages)
        {
            var role = m.TryGetValue("role", out var r) ? r : "user";
            var content = m.TryGetValue("content", out var c) ? c : "";
            var authorRole = role switch
            {
                "system" => AuthorRole.System,
                "assistant" => AuthorRole.Assistant,
                _ => AuthorRole.User,
            };
            history.AddMessage(authorRole, content);
        }
        return history;
    }

    /// <summary>从 SK 响应元数据提取 OpenAI 用量信息(键名与 OpenAI API 对齐:
    /// prompt_tokens/completion_tokens/total_tokens)。Metadata 缺失或非 OpenAI 用量类型时返回空字典。</summary>
    private static Dictionary<string, object?> ExtractUsage(ChatMessageContent resp)
    {
        var usage = new Dictionary<string, object?>();
        if (resp.Metadata != null && resp.Metadata.TryGetValue("Usage", out var u) && u is OpenAI.Chat.ChatTokenUsage tu)
        {
            usage["prompt_tokens"] = tu.InputTokenCount;
            usage["completion_tokens"] = tu.OutputTokenCount;
            usage["total_tokens"] = tu.TotalTokenCount;
        }
        return usage;
    }
}
