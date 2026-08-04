namespace DotnetAiService.Common;

/// <summary>应用配置(从 appsettings.json 的 App 节读取)。
/// <para>架构位置:对应 B 项目 app/config.py 的 Settings、C 项目 config/AppProperties.java;
/// 三语言读取同一套配置语义(KB 数据源、向量库、限流、OpenAI 兼容端点)。</para>
/// <para>关键设计点:在 Program.cs 里通过 Configure&lt;AppOptions&gt; 绑定 "App" 配置节,
/// 并额外注册成可直接注入的 singleton(否则 minimal API 的 GET 端点会把 AppOptions
/// 参数误推断为请求体而报错,见 Program.cs 中的说明)。</para>
/// </summary>
public class AppOptions
{
    /// <summary>知识库(KB)数据源配置:三级降级策略的入口,见 <see cref="KbOptions"/>。</summary>
    public KbOptions Kb { get; set; } = new();

    /// <summary>向量库实现标识。当前只实现了 "memory"(进程内 List 线性扫描),
    /// 预留字段,与 B/C 的 vector_store 配置对应;生产可换 Milvus/Qdrant 等。</summary>
    public string VectorStore { get; set; } = "memory";

    /// <summary>每租户每分钟请求上限(RateLimitMiddleware 用),超限返回 429。默认 20。</summary>
    public int RateLimitPerMinute { get; set; } = 20;

    /// <summary>OpenAI 兼容端点配置(Chat + Embedding 共用),见 <see cref="OpenAiOptions"/>。</summary>
    public OpenAiOptions OpenAI { get; set; } = new();

    /// <summary>知识库数据源配置。加载优先级(见 KnowledgeBase.LoadAsync):
    /// 显式 sourceOverride &gt; ContentPath(本地)&gt; ContentUrl(远程 manifest)&gt; SamplePath(本地样例兜底)。</summary>
    public class KbOptions
    {
        /// <summary>远程知识库 manifest.json 的 URL(GitHub 内容仓库)。
        /// manifest 是"目录索引":列出 domains → categories → topics 的相对路径,
        /// KnowledgeBase 按索引逐个拉取 topic JSON。远程失败自动降级到 SamplePath。</summary>
        public string ContentUrl { get; set; } = "https://raw.githubusercontent.com/nontracey/mianshi-zhilian-content/main/manifest.json";

        /// <summary>本地知识库路径(目录或 manifest 文件)。非空时优先于 ContentUrl,
        /// 用于离线开发/测试;默认空串表示走远程。</summary>
        public string ContentPath { get; set; } = "";

        /// <summary>本地样例知识库文件(仓库内 data/knowledge_base.sample.json)。
        /// 最后兜底数据源:远程不可达、又没配本地路径时用它,保证服务开箱可跑。
        /// 相对路径会从工作目录逐级向上查找 data/ 目录(见 KnowledgeBase.ResolveSamplePath)。</summary>
        public string SamplePath { get; set; } = "../data/knowledge_base.sample.json";
    }

    /// <summary>OpenAI 兼容 API 配置。BaseUrl 可指向 OpenAI 官方、通义、DeepSeek 等
    /// 任何 OpenAI 兼容端点,Chat 与 Embedding 共用同一个 OpenAIClient(Program.cs 注册)。</summary>
    public class OpenAiOptions
    {
        /// <summary>API 基地址,必须以 /v1 结尾(构造 OpenAIClient 时会 TrimEnd('/'))。
        /// 换模型供应商只改这里 + ApiKey + 模型名,业务代码不动。</summary>
        public string BaseUrl { get; set; } = "https://api.openai.com/v1";

        /// <summary>API Key。为空时 /health 的 llm_reachable 报 false(仅提示,不阻止启动)。</summary>
        public string ApiKey { get; set; } = "";

        /// <summary>对话模型名(RAG 生成、评估、Agent 编排、模拟回答都用它)。</summary>
        public string ChatModel { get; set; } = "gpt-4o-mini";

        /// <summary>向量模型名(入库切块向量化、提问向量化、语义缓存判定都用它)。</summary>
        public string EmbeddingModel { get; set; } = "text-embedding-3-small";
    }
}
