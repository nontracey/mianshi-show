using System.Text.Json;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>知识库 topic(与 B/C 同 schema)。</summary>
public class Topic
{
    public string Id { get; set; } = "";
    public string Domain { get; set; } = "";
    public string Category { get; set; } = "";
    public string Title { get; set; } = "";
    public string Summary { get; set; } = "";
    public List<string> Tags { get; set; } = new();
    public int Difficulty { get; set; } = 3;
    public string Status { get; set; } = "";
    public List<JsonElement> LearningCards { get; set; } = new();
    public List<JsonElement> RecallPrompts { get; set; } = new();
    public JsonElement Rubric { get; set; }
}

/// <summary>知识库加载器:manifest 驱动,三层数据源降级(与 B/C 同策略)。
/// 按租户隔离:_byTenant 是 Dictionary&lt;tenantId, Dictionary&lt;topicId, Topic&gt;&gt;,
/// 每个租户独立的知识库集合(见 TenantContext)。 </summary>
public class KnowledgeBase
{
    private readonly AppOptions _opts;
    private readonly HttpClient _http;
    private readonly Dictionary<string, Dictionary<string, Topic>> _byTenant = new();
    private readonly object _lock = new();
    public string ContentVersion { get; private set; } = "";

    public KnowledgeBase(AppOptions opts, HttpClient http)
    {
        _opts = opts;
        _http = http;
    }

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
            if (t.Status == "production")
            {
                map[t.Id] = t;
                prod++;
            }
        }
        return prod;
    }

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

    private async Task<List<Topic>> LoadFromLocalAsync(string root)
    {
        // 简化:如果是目录读 manifest.json;否则当文件读
        var manifestPath = Directory.Exists(root) ? Path.Combine(root, "manifest.json") : root;
        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(manifestPath));
        ContentVersion = doc.RootElement.GetProperty("contentVersion").GetString() ?? "local";
        var out_ = new List<Topic>();
        if (!Directory.Exists(root)) return out_;
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

    private async Task<List<Topic>> LoadFromRemoteAsync(string url)
    {
        var manifestJson = await _http.GetStringAsync(url);
        var doc = JsonDocument.Parse(manifestJson);
        ContentVersion = doc.RootElement.GetProperty("contentVersion").GetString() ?? "remote";
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

    public Topic? Get(string id)
    {
        var map = CurrentTenantMap;
        lock (_lock)
        {
            return map.TryGetValue(id, out var t) ? t : null;
        }
    }
    public List<Topic> List()
    {
        var map = CurrentTenantMap;
        lock (_lock)
        {
            return map.Values.ToList();
        }
    }
    public int Count
    {
        get
        {
            var map = CurrentTenantMap;
            lock (_lock) { return map.Count; }
        }
    }
}
