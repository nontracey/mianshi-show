using System.Text.Json;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>知识库 topic(与 B/C 同 schema)。
/// <para>一个 topic = 一个面试知识点(如 "volatile 关键字"),数据来自内容仓库的 topic JSON 文件。
/// 结构三要素:learningCards(RAG 知识内容,被 RagService 切块入库)、
/// recallPrompts(预置面试题,InterviewService 出题用)、rubric(评分标准,评估用)。</para>
/// <para>字段命名与 JSON schema 对齐(manifest 仓库是三语言共用的内容源)。</para>
/// </summary>
public class Topic
{
    /// <summary>topic 唯一 id,如 java.concurrency.volatile(点分层级)。</summary>
    public string Id { get; set; } = "";

    /// <summary>所属领域,如 java / ai-engineering。</summary>
    public string Domain { get; set; } = "";

    /// <summary>领域下的分类,如 concurrency。</summary>
    public string Category { get; set; } = "";

    /// <summary>topic 标题(人类可读),用于来源展示与上下文拼接。</summary>
    public string Title { get; set; } = "";

    /// <summary>topic 摘要:高度浓缩的要点,RagService 会把它作为独立 chunk 参与检索。</summary>
    public string Summary { get; set; } = "";

    /// <summary>标签列表(展示/筛选用)。</summary>
    public List<string> Tags { get; set; } = new();

    /// <summary>难度 1~5;recallPrompt 未标难度时用它兜底。</summary>
    public int Difficulty { get; set; } = 3;

    /// <summary>发布状态:只有 "production" 的 topic 会被加载进库(草稿/归档被过滤)。</summary>
    public string Status { get; set; } = "";

    /// <summary>学习卡片列表(RAG 的知识正文)。保留原始 JsonElement 延迟解析 ——
    /// 卡片 type 多样(explain/checklist/compareTable/code/diagram...),
    /// 切块时才按字段容错读取,避免为每种卡片建强类型。</summary>
    public List<JsonElement> LearningCards { get; set; } = new();

    /// <summary>预置面试题列表:{id, prompt, difficulty} 结构,出题直接取用(不调 LLM)。</summary>
    public List<JsonElement> RecallPrompts { get; set; } = new();

    /// <summary>评分标准:{mustHave 必答点, goodToHave 加分点, commonMistakes 常见错误}。
    /// LLM-as-Judge 评估时内嵌进 system prompt。已 Clone() 脱离原 JsonDocument,可长期持有。</summary>
    public JsonElement Rubric { get; set; }
}

/// <summary>知识库加载器:manifest 驱动,三层数据源降级(与 B/C 同策略)。
/// 按租户隔离:_byTenant 是 Dictionary&lt;tenantId, Dictionary&lt;topicId, Topic&gt;&gt;,
/// 每个租户独立的知识库集合(见 TenantContext)。
/// <para>架构位置:RAG 链路的最上游(内容 → topic),对应 B 项目 app/rag/loader.py、
/// C 项目 rag/Loader.java。下游是 RagService(切块/向量化/检索)。</para>
/// <para>数据源降级顺序(见 LoadAsync):显式 sourceOverride → App:Kb:ContentPath(本地目录)
/// → App:Kb:ContentUrl(远程 manifest,失败再降级)→ App:Kb:SamplePath(仓库内置样例)。
/// 保证任何环境下服务都能带着一份知识库跑起来。</para>
/// <para>manifest 驱动:内容仓库根上一个 manifest.json 索引(domains → categories → topics
/// 均为相对路径),加载时按索引逐级拉取,内容与代码解耦、可独立更新版本(contentVersion)。</para>
/// <para>线程安全:所有读写都在 _lock 内进行(singleton 注册,多请求并发访问)。</para>
/// </summary>
public class KnowledgeBase
{
    private readonly AppOptions _opts;
    private readonly HttpClient _http;
    /// <summary>租户 → (topicId → Topic) 双层字典:多租户隔离的存储本体。</summary>
    private readonly Dictionary<string, Dictionary<string, Topic>> _byTenant = new();
    /// <summary>全局锁:保护 _byTenant 及各租户 map 的并发读写。</summary>
    private readonly object _lock = new();
    /// <summary>当前已加载内容的版本号(manifest 的 contentVersion),/api/ingest 响应里返回。</summary>
    public string ContentVersion { get; private set; } = "";

    /// <summary>构造:AppOptions 决定数据源配置;HttpClient 由 DI 的 AddHttpClient 提供
    /// (Program.cs 注册,拉远程 manifest/topic 用)。</summary>
    public KnowledgeBase(AppOptions opts, HttpClient http)
    {
        _opts = opts;
        _http = http;
    }

    /// <summary>取当前请求租户的 topic 表,不存在则惰性创建空表。
    /// 租户 id 来自 TenantContext(AsyncLocal),因此同一方法在不同请求里拿到的是各自的表。</summary>
    private Dictionary<string, Topic> CurrentTenantMap
    {
        get
        {
            lock (_lock)
            {
                var tenant = TenantContext.CurrentTenant;
                if (!_byTenant.TryGetValue(tenant, out var map))
                {
                    map = new();
                    _byTenant[tenant] = map;
                }
                return map;
            }
        }
    }

    /// <summary>加载知识库到当前租户(幂等:重复调用先清空再重建)。
    /// 数据源优先级:sourceOverride(调用方显式指定)&gt; ContentPath(本地)&gt;
    /// ContentUrl(远程,异常时降级样例)&gt; SamplePath(本地样例兜底)。
    /// 只保留 status=="production" 的 topic;其余(草稿/归档)过滤掉。</summary>
    /// <param name="sourceOverride">显式数据源路径;null 表示按配置自动选择。</param>
    /// <returns>成功加载的 production topic 数。</returns>
    public async Task<int> LoadAsync(string? sourceOverride)
    {
        List<Topic> topics;
        if (!string.IsNullOrEmpty(sourceOverride))
        {
            topics = await LoadFromLocalAsync(sourceOverride);
        }
        else if (!string.IsNullOrEmpty(_opts.Kb.ContentPath))
        {
            topics = await LoadFromLocalAsync(_opts.Kb.ContentPath);
        }
        else if (!string.IsNullOrEmpty(_opts.Kb.ContentUrl))
        {
            // 远程优先;网络/格式异常时降级本地样例,保证入库流程可用
            try { topics = await LoadFromRemoteAsync(_opts.Kb.ContentUrl); }
            catch { topics = LoadFromSample(); }
        }
        else
        {
            topics = LoadFromSample();
        }

        var map = CurrentTenantMap;
        lock (_lock)
        {
            map.Clear();
        }
        int prod = 0;
        foreach (var t in topics)
        {
            // 只有 production 状态的内容对外可见(内容治理:草稿/归档不入库)
            if (t.Status == "production")
            {
                map[t.Id] = t;
                prod++;
            }
        }
        return prod;
    }

    /// <summary>第三层兜底数据源:仓库内置的样例知识库文件(data/knowledge_base.sample.json)。
    /// 单文件结构:{contentVersion, topics:[...]}直接解析。</summary>
    private List<Topic> LoadFromSample()
    {
        var path = ResolveSamplePath();
        var json = File.ReadAllText(path);
        var doc = JsonDocument.Parse(json);
        ContentVersion = doc.RootElement.GetProperty("contentVersion").GetString() ?? "sample";
        var out_ = new List<Topic>();
        foreach (var t in doc.RootElement.GetProperty("topics").EnumerateArray())
        {
            out_.Add(ParseTopic(t));
        }
        return out_;
    }

    /// <summary>本地数据源:root 为目录时读其下 manifest.json,按 domains → categories → topics
    /// 的相对路径索引逐个读 topic 文件(manifest 驱动,与远程加载同构)。
    /// 注:root 若是单个文件,只读取其 contentVersion,不展开 topic(简化处理)。</summary>
    private async Task<List<Topic>> LoadFromLocalAsync(string root)
    {
        // 简化:如果是目录读 manifest.json;否则当文件读
        var manifestPath = Directory.Exists(root) ? Path.Combine(root, "manifest.json") : root;
        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(manifestPath));
        ContentVersion = doc.RootElement.GetProperty("contentVersion").GetString() ?? "local";
        var out_ = new List<Topic>();
        if (!Directory.Exists(root)) return out_;
        // 按 manifest 索引逐级展开:domain 文件 → category 列表 → topic 文件路径
        foreach (var d in doc.RootElement.GetProperty("domains").EnumerateArray())
        {
            var df = Path.Combine(root, d.GetProperty("entry").GetString()!);
            if (!File.Exists(df)) continue;
            var dDoc = JsonDocument.Parse(await File.ReadAllTextAsync(df));
            foreach (var c in dDoc.RootElement.GetProperty("categories").EnumerateArray())
            {
                foreach (var tp in c.GetProperty("topics").EnumerateArray())
                {
                    var tf = Path.Combine(root, tp.GetString()!);
                    if (File.Exists(tf))
                    {
                        var tDoc = JsonDocument.Parse(await File.ReadAllTextAsync(tf));
                        out_.Add(ParseTopic(tDoc.RootElement));
                    }
                }
            }
        }
        return out_;
    }

    /// <summary>远程数据源:从 ContentUrl 拉 manifest.json,再以 manifest 所在目录为 baseUrl
    /// 拼接相对路径,逐级拉 domain/topic JSON。
    /// 容错设计:单个 domain/topic 拉取失败只跳过该条(空 catch),不让一个坏文件拖垮整体加载。</summary>
    private async Task<List<Topic>> LoadFromRemoteAsync(string url)
    {
        var manifestJson = await _http.GetStringAsync(url);
        var doc = JsonDocument.Parse(manifestJson);
        ContentVersion = doc.RootElement.GetProperty("contentVersion").GetString() ?? "remote";
        // manifest 里都是相对路径,用 url 去掉最后一个 '/' 后的文件名得到基目录
        var baseUrl = url[..url.LastIndexOf('/')];
        var out_ = new List<Topic>();
        foreach (var d in doc.RootElement.GetProperty("domains").EnumerateArray())
        {
            var dUrl = $"{baseUrl}/{d.GetProperty("entry").GetString()}";
            try
            {
                var dJson = await _http.GetStringAsync(dUrl);
                var dDoc = JsonDocument.Parse(dJson);
                foreach (var c in dDoc.RootElement.GetProperty("categories").EnumerateArray())
                {
                    foreach (var tp in c.GetProperty("topics").EnumerateArray())
                    {
                        var tUrl = $"{baseUrl}/{tp.GetString()}";
                        try
                        {
                            var tJson = await _http.GetStringAsync(tUrl);
                            var tDoc = JsonDocument.Parse(tJson);
                            out_.Add(ParseTopic(tDoc.RootElement));
                        }
                        catch { }
                    }
                }
            }
            catch { }
        }
        return out_;
    }

    /// <summary>把 topic JSON 解析成 Topic 对象。所有可选字段都用 TryGetProperty 容错
    /// (内容仓库里老数据可能缺字段),缺字段给安全默认值而不是抛异常。</summary>
    private static Topic ParseTopic(JsonElement e)
    {
        var t = new Topic
        {
            Id = e.GetProperty("id").GetString() ?? "",
            Domain = e.TryGetProperty("domain", out var d) ? d.GetString() ?? "" : "",
            Category = e.TryGetProperty("category", out var c) ? c.GetString() ?? "" : "",
            Title = e.TryGetProperty("title", out var ti) ? ti.GetString() ?? "" : "",
            Summary = e.TryGetProperty("summary", out var s) ? s.GetString() ?? "" : "",
            Status = e.TryGetProperty("status", out var st) ? st.GetString() ?? "" : "",
            Difficulty = e.TryGetProperty("difficulty", out var df) ? df.GetInt32() : 3,
        };
        if (e.TryGetProperty("tags", out var tags))
            t.Tags = tags.EnumerateArray().Select(x => x.GetString() ?? "").ToList();
        if (e.TryGetProperty("learningCards", out var cards))
            t.LearningCards = cards.EnumerateArray().ToList();
        if (e.TryGetProperty("recallPrompts", out var rp))
            t.RecallPrompts = rp.EnumerateArray().ToList();
        if (e.TryGetProperty("rubric", out var rb))
            // Clone():EnumerateArray 得到的 JsonElement 引用着父 JsonDocument 的内存,
            // Clone 出一份独立副本,文档释放后 Topic 仍可安全持有 rubric
            t.Rubric = rb.Clone();
        return t;
    }

    private string ResolveSamplePath()
    {
        var p = _opts.Kb.SamplePath;
        if (Path.IsPathRooted(p)) return p;
        // 相对路径：从工作目录逐级向上找 data/<文件名>，兼容从仓库根或子项目目录启动，
        // 不依赖具体 CWD（换电脑/换目录 clone 下来都能跑）。
        var tail = Path.GetFileName(p);
        for (var cur = new DirectoryInfo(Directory.GetCurrentDirectory()); cur != null; cur = cur.Parent)
        {
            var candidate = Path.Combine(cur.FullName, "data", tail);
            if (File.Exists(candidate)) return candidate;
        }
        return Path.GetFullPath(p);
    }

    /// <summary>按 id 取当前租户的 topic;不存在返回 null(调用方决定 404 或降级)。</summary>
    public Topic? Get(string id)
    {
        var map = CurrentTenantMap;
        lock (_lock)
        {
            return map.TryGetValue(id, out var t) ? t : null;
        }
    }

    /// <summary>当前租户的全部 topic 快照(RagService.IngestAsync 遍历切块用)。</summary>
    public List<Topic> List()
    {
        var map = CurrentTenantMap;
        lock (_lock)
        {
            return map.Values.ToList();
        }
    }

    /// <summary>当前租户已加载的 topic 数(/health 的 kb_source 状态展示用)。</summary>
    public int Count
    {
        get
        {
            var map = CurrentTenantMap;
            lock (_lock) { return map.Count; }
        }
    }
}
