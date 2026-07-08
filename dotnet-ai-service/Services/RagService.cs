using System.Text;
using System.Text.Json;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>chunk + metadata。</summary>
public class Chunk
{
    public string Text { get; set; } = "";
    public Dictionary<string, object> Metadata { get; set; } = new();
}

/// <summary>RAG 服务:切分 + 内存向量库 + 混合检索 + 生成(与 B/C 同构)。</summary>
public class RagService
{
    private readonly LlmClient _llm;
    private readonly KnowledgeBase _kb;
    private readonly List<(Chunk chunk, float[] emb)> _store = new();
    private readonly List<List<string>> _bm25Tokens = new();
    private List<Chunk> _allChunks = new();

    public RagService(LlmClient llm, KnowledgeBase kb)
    {
        _llm = llm;
        _kb = kb;
    }

    public int ChunkCount => _store.Count;

    public async Task<(int topics, int chunks)> IngestAsync()
    {
        var topics = _kb.List();
        var chunks = new List<Chunk>();
        foreach (var t in topics)
        {
            chunks.AddRange(SplitTopic(t));
        }
        _allChunks = chunks;
        _store.Clear();
        _bm25Tokens.Clear();
        // 批量 embed
        for (int i = 0; i < chunks.Count; i += 64)
        {
            var batch = chunks.Skip(i).Take(64).Select(c => c.Text).ToList();
            var embs = await _llm.EmbedAsync(batch);
            for (int j = 0; j < batch.Count; j++)
            {
                _store.Add((chunks[i + j], embs[j]));
                _bm25Tokens.Add(Tokenize(batch[j]));
            }
        }
        return (topics.Count, chunks.Count);
    }

    private static List<Chunk> SplitTopic(Topic t)
    {
        var out_ = new List<Chunk>();
        var baseMeta = new Dictionary<string, object>
        {
            ["topic_id"] = t.Id, ["domain"] = t.Domain, ["category"] = t.Category,
            ["title"] = t.Title, ["tags"] = t.Tags, ["difficulty"] = t.Difficulty,
        };
        var wholeTypes = new HashSet<string> { "checklist", "compareTable", "code", "diagram" };
        foreach (var card in t.LearningCards)
        {
            var ctype = card.TryGetProperty("type", out var tp) ? tp.GetString() ?? "explain" : "explain";
            var title = card.TryGetProperty("title", out var ti) ? ti.GetString() ?? "" : "";
            var content = card.TryGetProperty("content", out var c) ? c.GetString() ?? "" : "";
            if (string.IsNullOrEmpty(content)) continue;
            var meta = new Dictionary<string, object>(baseMeta) { ["card_type"] = ctype, ["card_title"] = title };
            if (wholeTypes.Contains(ctype))
            {
                out_.Add(new Chunk { Text = content, Metadata = meta });
            }
            else
            {
                foreach (var piece in SplitRecursive(content, 500, 80))
                    out_.Add(new Chunk { Text = piece, Metadata = meta });
            }
        }
        if (!string.IsNullOrEmpty(t.Summary))
        {
            out_.Add(new Chunk { Text = t.Summary, Metadata = new Dictionary<string, object>(baseMeta) { ["card_type"] = "summary" } });
        }
        return out_;
    }

    private static List<string> SplitRecursive(string text, int size, int overlap)
    {
        if (text.Length <= size) return new List<string> { text };
        var seps = new[] { "\n\n", "\n", "。", ".", "!", "?", ";", " " };
        var pieces = new List<string> { text };
        foreach (var sep in seps)
        {
            var next = new List<string>();
            foreach (var p in pieces)
            {
                if (p.Length <= size) next.Add(p);
                else next.AddRange(p.Split(sep).Where(s => !string.IsNullOrEmpty(s)));
            }
            pieces = next;
            if (pieces.All(p => p.Length <= size)) break;
        }
        // 兜底硬切
        var fin = new List<string>();
        foreach (var p in pieces)
        {
            if (p.Length <= size) fin.Add(p);
            else for (int i = 0; i < p.Length; i += size) fin.Add(p.Substring(i, Math.Min(size, p.Length - i)));
        }
        return fin;
    }

    public async Task<(string answer, List<Dictionary<string, object>> sources)> AskAsync(string question, int topK = 4, string mode = "hybrid")
    {
        var docs = await RetrieveAsync(question, topK, mode);
        var context = BuildContext(docs);
        var system = $"""
            你是严谨的技术面试知识助手。只依据【上下文】回答,标注来源条目 id。
            上下文没有的内容,直接说"知识库中没有相关内容",不要编造。

            【上下文】
            {context}
            """;
        var messages = new List<Dictionary<string, string>>
        {
            new() { ["role"] = "system", ["content"] = system },
            new() { ["role"] = "user", ["content"] = question },
        };
        var (answer, _) = await _llm.ChatAsync(messages, 0.3);
        var sources = ExtractSources(docs);
        return (answer, sources);
    }

    public async Task<List<Chunk>> RetrieveAsync(string query, int topK, string mode)
    {
        if (_store.Count == 0) return new();
        var qEmb = (await _llm.EmbedAsync(new List<string> { query }))[0];
        var vecK = Math.Max(topK * 2, 8);
        var vec = _store.Select((e, i) => (e.chunk, score: Cosine(qEmb, e.emb)))
                       .OrderByDescending(x => x.score).Take(vecK).ToList();

        if (mode == "vector") return vec.Take(topK).Select(x => x.chunk).ToList();

        // BM25
        var qTokens = Tokenize(query);
        var bm = new List<(int idx, double score)>();
        for (int i = 0; i < _bm25Tokens.Count; i++)
        {
            var tf = qTokens.Sum(t => _bm25Tokens[i].Count(x => x == t));
            var s = _bm25Tokens[i].Count == 0 ? 0 : (double)tf / _bm25Tokens[i].Count;
            if (s > 0) bm.Add((i, s));
        }
        bm = bm.OrderByDescending(x => x.score).Take(vecK).ToList();

        // RRF
        var scores = new Dictionary<string, double>();
        var docsByKey = new Dictionary<string, Chunk>();
        for (int i = 0; i < vec.Count; i++)
        {
            var key = Key(vec[i].chunk);
            scores[key] = scores.GetValueOrDefault(key) + 1.0 / (60 + i + 1);
            docsByKey.TryAdd(key, vec[i].chunk);
        }
        for (int i = 0; i < bm.Count; i++)
        {
            var chunk = _store[bm[i].idx].chunk;
            var key = Key(chunk);
            scores[key] = scores.GetValueOrDefault(key) + 1.0 / (60 + i + 1);
            docsByKey.TryAdd(key, chunk);
        }
        return scores.OrderByDescending(x => x.Value).Take(topK).Select(x => docsByKey[x.Key]).ToList();
    }

    private static string BuildContext(List<Chunk> docs)
    {
        if (docs.Count == 0) return "(空)";
        var sb = new StringBuilder();
        for (int i = 0; i < docs.Count; i++)
        {
            var m = docs[i].Metadata;
            sb.AppendLine($"[{i + 1}] id={m.GetValueOrDefault("topic_id")} | {m.GetValueOrDefault("title")}({m.GetValueOrDefault("card_type")})");
            sb.AppendLine(docs[i].Text);
            sb.AppendLine();
        }
        return sb.ToString();
    }

    private static List<Dictionary<string, object>> ExtractSources(List<Chunk> docs)
    {
        var seen = new HashSet<string>();
        var out_ = new List<Dictionary<string, object>>();
        foreach (var d in docs)
        {
            var tid = d.Metadata.GetValueOrDefault("topic_id")?.ToString() ?? "";
            if (!string.IsNullOrEmpty(tid) && seen.Add(tid))
            {
                out_.Add(new Dictionary<string, object>
                {
                    ["id"] = tid,
                    ["topic"] = d.Metadata.GetValueOrDefault("title") ?? "",
                    ["card_type"] = d.Metadata.GetValueOrDefault("card_type") ?? "",
                });
            }
        }
        return out_;
    }

    private static double Cosine(float[] a, float[] b)
    {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.Length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.Sqrt(na) * Math.Sqrt(nb));
    }

    private static string Key(Chunk c) => c.Text.Length > 64 ? c.Text[..64] : c.Text;

    private static List<string> Tokenize(string text)
    {
        var out_ = new List<string>();
        var buf = new StringBuilder();
        foreach (var ch in text)
        {
            if (ch >= '一' && ch <= '鿿')
            {
                if (buf.Length > 0) { out_.Add(buf.ToString().ToLower()); buf.Clear(); }
                out_.Add(ch.ToString());
            }
            else if (char.IsLetterOrDigit(ch)) buf.Append(ch);
            else { if (buf.Length > 0) { out_.Add(buf.ToString().ToLower()); buf.Clear(); } }
        }
        if (buf.Length > 0) out_.Add(buf.ToString().ToLower());
        return out_;
    }
}

public class InterviewService
{
    private readonly LlmClient _llm;
    private readonly KnowledgeBase _kb;

    public InterviewService(LlmClient llm, KnowledgeBase kb)
    {
        _llm = llm;
        _kb = kb;
    }

    public List<Dictionary<string, object>> GenerateQuestions(string topicId, int? difficulty, int count)
    {
        var t = _kb.Get(topicId) ?? throw new ArgumentException($"topic 不存在:{topicId}");
        var prompts = t.RecallPrompts.ToList();
        if (difficulty.HasValue)
        {
            prompts = prompts.Where(p => p.TryGetProperty("difficulty", out var d) && d.GetInt32() == difficulty.Value).ToList();
        }
        var out_ = new List<Dictionary<string, object>>();
        for (int i = 0; i < Math.Min(count, prompts.Count); i++)
        {
            var p = prompts[i];
            var qid = p.TryGetProperty("id", out var id) ? id.GetString() ?? $"{topicId}.recall.{i + 1}" : $"{topicId}.recall.{i + 1}";
            var prompt = p.TryGetProperty("prompt", out var pr) ? pr.GetString() ?? "" : "";
            var diff = p.TryGetProperty("difficulty", out var df) ? df.GetInt32() : t.Difficulty;
            out_.Add(new Dictionary<string, object> { ["question_id"] = qid, ["prompt"] = prompt, ["difficulty"] = diff });
        }
        return out_;
    }

    public async Task<Dictionary<string, object>> EvaluateAsync(string questionId, string userAnswer)
    {
        var topicId = ExtractTopicId(questionId);
        var t = _kb.Get(topicId) ?? throw new ArgumentException($"topic 不存在:{topicId}");
        if (t.Rubric.ValueKind == JsonValueKind.Undefined || !t.Rubric.TryGetProperty("mustHave", out _))
            throw new ArgumentException($"topic 缺少 rubric.mustHave:{topicId}");

        var system = "你是资深技术面试官,按给定评分标准客观评估,输出严格 JSON。\n" +
            "评分标准:\n" +
            "- 必答点(must_have):" + t.Rubric.GetProperty("mustHave") + "\n" +
            "- 加分点(good_to_have):" + t.Rubric.GetProperty("goodToHave") + "\n" +
            "- 常见错误(common_mistakes):" + t.Rubric.GetProperty("commonMistakes") + "\n" +
            "输出 JSON:{\"score\":0-100,\"hit_points\":[],\"missed\":[],\"mistakes\":[],\"feedback\":\"\"}";
        var questionText = "";
        foreach (var p in t.RecallPrompts)
        {
            if (p.TryGetProperty("id", out var id) && id.GetString() == questionId)
            {
                questionText = p.TryGetProperty("prompt", out var pr) ? pr.GetString() ?? "" : "";
                break;
            }
        }
        var messages = new List<Dictionary<string, string>>
        {
            new() { ["role"] = "system", ["content"] = system },
            new() { ["role"] = "user", ["content"] = $"题目:{questionText}\n\n候选人回答:\n{userAnswer}" },
        };
        string content;
        try { content = await _llm.ChatJsonAsync(messages, 0.0); }
        catch (Exception e) { return Degraded($"评估服务不可用:{e.Message}"); }
        try
        {
            var obj = JsonDocument.Parse(content).RootElement;
            return new Dictionary<string, object>
            {
                ["score"] = obj.GetProperty("score").GetInt32(),
                ["hit"] = obj.GetProperty("hit_points").EnumerateArray().Select(x => x.GetString() ?? "").ToList(),
                ["missed"] = obj.GetProperty("missed").EnumerateArray().Select(x => x.GetString() ?? "").ToList(),
                ["mistakes"] = obj.GetProperty("mistakes").EnumerateArray().Select(x => x.GetString() ?? "").ToList(),
                ["feedback"] = obj.GetProperty("feedback").GetString() ?? "",
                ["degraded"] = false,
            };
        }
        catch { return Degraded("评估输出非合法 JSON"); }
    }

    private static Dictionary<string, object> Degraded(string feedback) => new()
    {
        ["score"] = 0, ["hit"] = new List<string>(), ["missed"] = new List<string>(),
        ["mistakes"] = new List<string>(), ["feedback"] = feedback, ["degraded"] = true,
    };

    public static string ExtractTopicId(string questionId)
    {
        var parts = questionId.Split('.');
        if (parts.Length >= 3 && parts[^2] == "recall") return string.Join(".", parts[..^2]);
        return questionId.Contains('.') ? questionId[..questionId.LastIndexOf('.')] : questionId;
    }
}
