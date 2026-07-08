using System.Text.Json;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>OpenAI 兼容 LLM 客户端(HttpClient 直调,与 B 的 openai SDK 等价)。
/// 支持通义/DeepSeek/OpenAI/本地模型,靠 BaseUrl 切换。</summary>
public class LlmClient
{
    private readonly HttpClient _http;
    private readonly AppOptions _opts;

    public LlmClient(HttpClient http, AppOptions opts)
    {
        _http = http;
        _opts = opts;
        _http.BaseAddress = new Uri(opts.OpenAI.BaseUrl.TrimEnd('/') + "/");
        _http.DefaultRequestHeaders.Add("Authorization", $"Bearer {opts.OpenAI.ApiKey}");
    }

    public string ChatModel => _opts.OpenAI.ChatModel;

    public async Task<(string content, Dictionary<string, object?> usage)> ChatAsync(
        List<Dictionary<string, string>> messages, double temperature = 0.0)
    {
        var body = new
        {
            model = _opts.OpenAI.ChatModel,
            messages,
            temperature,
        };
        var resp = await _http.PostAsync("chat/completions",
            new StringContent(JsonSerializer.Serialize(body), System.Text.Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
        var json = await resp.Content.ReadFromJsonAsync<JsonDocument>();
        var content = json?.RootElement.GetProperty("choices")[0].GetProperty("message").GetProperty("content").GetString() ?? "";
        var usage = new Dictionary<string, object?>();
        if (json?.RootElement.TryGetProperty("usage", out var u) == true)
        {
            usage["prompt_tokens"] = u.GetProperty("prompt_tokens").GetInt32();
            usage["completion_tokens"] = u.GetProperty("completion_tokens").GetInt32();
            usage["total_tokens"] = u.GetProperty("total_tokens").GetInt32();
        }
        return (content, usage);
    }

    public async Task<List<float[]>> EmbedAsync(List<string> texts)
    {
        if (texts.Count == 0) return new();
        var body = new { model = _opts.OpenAI.EmbeddingModel, input = texts };
        var resp = await _http.PostAsync("embeddings",
            new StringContent(JsonSerializer.Serialize(body), System.Text.Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
        var json = await resp.Content.ReadFromJsonAsync<JsonDocument>();
        var out_ = new List<float[]>();
        if (json?.RootElement.TryGetProperty("data", out var data) == true)
        {
            foreach (var d in data.EnumerateArray())
            {
                var emb = d.GetProperty("embedding").EnumerateArray().Select(x => x.GetSingle()).ToArray();
                out_.Add(emb);
            }
        }
        return out_;
    }

    public async Task<string> ChatJsonAsync(List<Dictionary<string, string>> messages, double temperature = 0.0)
    {
        var body = new
        {
            model = _opts.OpenAI.ChatModel,
            messages,
            temperature,
            response_format = new { type = "json_object" },
        };
        var resp = await _http.PostAsync("chat/completions",
            new StringContent(JsonSerializer.Serialize(body), System.Text.Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
        var json = await resp.Content.ReadFromJsonAsync<JsonDocument>();
        return json?.RootElement.GetProperty("choices")[0].GetProperty("message").GetProperty("content").GetString() ?? "";
    }
}
