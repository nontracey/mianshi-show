namespace DotnetAiService.Common;

/// <summary>应用配置(从 appsettings.json 的 App 节读取)。</summary>
public class AppOptions
{
    public KbOptions Kb { get; set; } = new();
    public string VectorStore { get; set; } = "memory";
    public int RateLimitPerMinute { get; set; } = 20;
    public OpenAiOptions OpenAI { get; set; } = new();

    public class KbOptions
    {
        public string ContentUrl { get; set; } = "https://raw.githubusercontent.com/nontracey/mianshi-zhilian-content/main/manifest.json";
        public string ContentPath { get; set; } = "";
        public string SamplePath { get; set; } = "../data/knowledge_base.sample.json";
    }

    public class OpenAiOptions
    {
        public string BaseUrl { get; set; } = "https://api.openai.com/v1";
        public string ApiKey { get; set; } = "";
        public string ChatModel { get; set; } = "gpt-4o-mini";
        public string EmbeddingModel { get; set; } = "text-embedding-3-small";
    }
}
