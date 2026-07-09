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
/// JSON 输出走 ResponseFormat=ChatResponseFormat.JsonObject。 </summary>
public class LlmClient
{
    private readonly Kernel _kernel;
    private readonly IChatCompletionService _chat;
    private readonly ITextEmbeddingGenerationService _embed;
    private readonly AppOptions _opts;

    public LlmClient(Kernel kernel, AppOptions opts)
    {
        _kernel = kernel;
        _opts = opts;
        _chat = kernel.GetRequiredService<IChatCompletionService>();
        _embed = kernel.GetRequiredService<ITextEmbeddingGenerationService>();
    }

    /** 暴露 Kernel 供 AgentService 注册插件/InvokePromptAsync 用。 */
    public Kernel Kernel => _kernel;

    public string ChatModel => _opts.OpenAI.ChatModel;

    public async Task<(string content, Dictionary<string, object?> usage)> ChatAsync(
        List<Dictionary<string, string>> messages, double temperature = 0.0)
    {
        var history = ToChatHistory(messages);
        var settings = new OpenAIPromptExecutionSettings { Temperature = temperature };
        var resp = await _chat.GetChatMessageContentAsync(history, settings);
        return (resp.Content ?? "", ExtractUsage(resp));
    }

    public async Task<List<float[]>> EmbedAsync(List<string> texts)
    {
        if (texts.Count == 0) return new();
        var embs = await _embed.GenerateEmbeddingsAsync(texts);
        return embs.Select(e => e.ToArray()).ToList();
    }

    public async Task<string> ChatJsonAsync(List<Dictionary<string, string>> messages, double temperature = 0.0)
    {
        // OpenAI SDK 2.x 的 ResponseFormat API 在 SK 1.78 下不稳定,改靠 prompt 约束 JSON
        // (调用方 InterviewService 的 system prompt 已强制"输出严格 JSON")
        var (content, _) = await ChatAsync(messages, temperature);
        return content;
    }

    /// <summary>流式 chat:逐块 yield content(供 SSE 转发)。</summary>
    public async IAsyncEnumerable<string> ChatStreamAsync(
        List<Dictionary<string, string>> messages, double temperature = 0.3,
        [EnumeratorCancellation] CancellationToken ct = default)
    {
        var history = ToChatHistory(messages);
        var settings = new OpenAIPromptExecutionSettings { Temperature = temperature };
        await foreach (var chunk in _chat.GetStreamingChatMessageContentsAsync(history, settings, cancellationToken: ct))
        {
            if (!string.IsNullOrEmpty(chunk.Content)) yield return chunk.Content;
        }
    }

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
