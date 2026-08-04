using System.Text;
using System.Text.Json;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>知识库切块(检索/生成的最小单元)。
/// <para>由 RagService.SplitTopic 从 topic 的学习卡片切分而来,
/// Text 是切块正文,Metadata 携带溯源与过滤信息(topic_id/title/card_type/tenant_id 等)。</para>
/// <para>对应 B 项目 app/rag/store.py 中的文档块结构、C 项目 rag/VectorStoreService.java 的 Chunk。</para>
/// </summary>
public class Chunk
{
    /// <summary>切块正文:向量化的输入,也是 RAG 上下文拼接与 LLM 重排展示的内容。</summary>
    public string Text { get; set; } = "";

    /// <summary>元数据:topic_id/domain/category/title/tags/difficulty/card_type/card_title,
    /// 入库时再追加 tenant_id。用途:① 检索时按租户过滤;② 生成时拼上下文标注来源;
    /// ③ 响应里返回 sources 溯源;④ RRF 融合时按内容去重。</summary>
    public Dictionary<string, object> Metadata { get; set; } = new();
}

/// <summary>RAG 服务:切分 + 内存向量库 + 混合检索 + 生成(与 B/C 同构)。
/// <para>架构位置:本项目的核心文件,一条流水线覆盖 RAG 全链路:
/// 知识加载(KnowledgeBase)→ 切块(SplitTopic/SplitRecursive)→ 向量化入库(IngestAsync)
/// → 混合检索(RetrieveAsync:向量 + BM25 + RRF 融合,可选 LLM 重排)
/// → 受控生成(GenerateAsync/GenerateStreamAsync,防幻觉 Prompt)。</para>
/// <para>对应关系:B 项目拆成 app/rag/splitter.py(切块)、store.py(向量库)、
/// retriever.py(混合检索)、generator.py(生成)四个模块;
/// C 项目拆成 rag/Splitter.java、VectorStoreService.java、HybridRetriever.java、Generator.java。
/// D 项目用一个类收拢,逻辑逐段对齐。</para>
/// <para>关键设计点:
/// ① 内存向量库用 List&lt;(Chunk, float[])&gt; 元组 + 线性扫描 —— 不引第三方向量库,
/// 演示规模(几百个 chunk)下暴力余弦足够快,且切块/向量同索引存放、结构一目了然;
/// 生产换 ANN 索引(Milvus/Qdrant/pgvector)即可,检索接口不用动。
/// ② BM25 是简化实现(词频密度,不算 IDF/k1/b),与向量结果用 RRF 按排名融合,
/// 规避两路分数量纲不一致的问题。
/// ③ 多租户:每个 chunk 带 tenant_id metadata,入库清理与检索过滤都按
/// TenantContext.CurrentTenant 生效,不同租户数据互不可见。</para>
/// </summary>
public class RagService
{
    private readonly LlmClient _llm;
    private readonly KnowledgeBase _kb;
    /// <summary>内存向量库:元素是 (chunk, 向量) 元组。
    /// 为什么用 List 元组而不是字典/专业向量库:切块与向量按下标一一对应、追加即入库,
    /// 线性扫描在几百条规模下延迟可忽略,零依赖、易读;换真实向量库只需替换本字段的读写。</summary>
    private readonly List<(Chunk chunk, float[] emb)> _store = new();
    /// <summary>BM25 用的分词结果,与 _store 严格同索引(入库/删除时同步维护)。</summary>
    private readonly List<List<string>> _bm25Tokens = new();
    /// <summary>最近一次入库产生的全部 chunk(含 metadata)。当前仅供调试观察,无读取方。</summary>
    private List<Chunk> _allChunks = new();

    public RagService(LlmClient llm, KnowledgeBase kb)
    {
        _llm = llm;
        _kb = kb;
    }

    /// <summary>当前租户在库的 chunk 数。
    /// 租户隔离读取侧:只统计 metadata.tenant_id == 当前租户的条目。
    /// /health 的 vector_store_ready、/api/ask 与 /api/agent/session 的"先 ingest"前置检查都靠它。</summary>
    public int ChunkCount
    {
        get
        {
            var tenant = TenantContext.CurrentTenant;
            return _store.Count(e => tenant.Equals(e.chunk.Metadata.GetValueOrDefault("tenant_id")));
        }
    }

    /// <summary>知识入库(写入侧,租户隔离在这里生效):
    /// 拉当前租户已加载的全部 topic → 切块 → 打 tenant_id → 清旧 → 批量向量化入 _store。
    /// 对应 B 项目 ingest 流程、C 项目 RagController 的 /api/ingest 内部逻辑。</summary>
    /// <returns>(入库 topic 数, 切块数),供 /api/ingest 响应展示。</returns>
    /// <exception cref="Exception">Embedding 调用失败等,由端点捕获转 500。</exception>
    public async Task<(int topics, int chunks)> IngestAsync()
    {
        var tenant = TenantContext.CurrentTenant;
        var topics = _kb.List();
        var chunks = new List<Chunk>();
        foreach (var t in topics)
        {
            chunks.AddRange(SplitTopic(t));
        }
        // 给每个 chunk 加 tenant_id metadata(按租户隔离检索)
        foreach (var c in chunks)
        {
            c.Metadata["tenant_id"] = tenant;
        }
        _allChunks = chunks;
        // 清当前租户的旧数据(倒序移除保索引,_store 和 _bm25Tokens 同步)。
        // 倒序遍历的原因:RemoveAt 会让后续元素前移,正序遍历会跳过元素/越界。
        for (int i = _store.Count - 1; i >= 0; i--)
        {
            if (tenant.Equals(_store[i].chunk.Metadata.GetValueOrDefault("tenant_id")))
            {
                _store.RemoveAt(i);
                _bm25Tokens.RemoveAt(i);
            }
        }
        // 批量 embed:每批 64 条,限制单次请求体大小、避免超过服务端批量上限;
        // 入库时 _store 与 _bm25Tokens 成对 Add,保证两者下标永远对齐
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

    /// <summary>把一个 topic 切成若干 chunk(对应 B 项目 splitter.py / C 项目 Splitter.java)。
    /// 规则:遍历 topic 的学习卡片(learningCards),按卡片类型分流 ——
    /// 结构化卡片(checklist/compareTable/code/diagram)整体成块(切开会让表格/代码失去语义),
    /// 普通文本卡片按 SplitRecursive(500, 80) 递归切;最后把 topic 摘要作为 summary 块补入。
    /// 每个 chunk 都带 topic 级元数据(溯源 + 展示用)。</summary>
    /// <param name="t">知识库 topic(已按租户加载)。</param>
    /// <returns>该 topic 的全部 chunk。</returns>
    private static List<Chunk> SplitTopic(Topic t)
    {
        var out_ = new List<Chunk>();
        // topic 级公共元数据:所有 chunk 继承,供溯源/过滤/来源展示
        var baseMeta = new Dictionary<string, object>
        {
            ["topic_id"] = t.Id, ["domain"] = t.Domain, ["category"] = t.Category,
            ["title"] = t.Title, ["tags"] = t.Tags, ["difficulty"] = t.Difficulty,
        };
        // 这些卡片类型是结构化内容,整块保留不切分
        var wholeTypes = new HashSet<string> { "checklist", "compareTable", "code", "diagram" };
        foreach (var card in t.LearningCards)
        {
            // learningCards 保留原始 JsonElement,这里按字段容错解析(缺字段给默认值)
            var ctype = card.TryGetProperty("type", out var tp) ? tp.GetString() ?? "explain" : "explain";
            var title = card.TryGetProperty("title", out var ti) ? ti.GetString() ?? "" : "";
            var content = card.TryGetProperty("content", out var c) ? c.GetString() ?? "" : "";
            if (string.IsNullOrEmpty(content)) continue;
            // 每个 chunk 拷贝独立 meta(避免共享同一字典被串改),并记录卡片级信息
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
        // topic 摘要是高度浓缩的要点,单独成块参与检索
        if (!string.IsNullOrEmpty(t.Summary))
        {
            out_.Add(new Chunk { Text = t.Summary, Metadata = new Dictionary<string, object>(baseMeta) { ["card_type"] = "summary" } });
        }
        return out_;
    }

    /// <summary>递归切块(对应 B 项目 splitter.py 的递归字符切分 / C 项目 Splitter.java):
    /// 优先在语义边界断开。按分隔符优先级 ["\n\n","\n","。",".","!","?",";"," "] 逐级尝试:
    /// 每一级只切仍超长(size)的片段,已达标片段保留;一旦全部 ≤size 立即停止,
    /// 尽量让切点落在段落/句子边界上。最后仍有超长片段则按 size 硬切兜底。</summary>
    /// <param name="text">待切文本。</param>
    /// <param name="size">单块长度上限(字符数)。</param>
    /// <param name="overlap">重叠窗口大小:与 B/C 的切分签名保持一致而保留,
    /// 本简化实现按分隔符边界切分,不产生重叠窗口。</param>
    /// <returns>全部 ≤size 的片段(不含空串)。</returns>
    private static List<string> SplitRecursive(string text, int size, int overlap)
    {
        if (text.Length <= size) return new List<string> { text };
        // 分隔符按语义粒度从粗到细:段落 → 行 → 句号(中文/英文) → 分号 → 空格
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

    /// <summary>一步式问答便捷封装:检索 + 生成(内部给 AgentService 等调用)。
    /// 端点 /api/ask 不走这里,因为端点需要在检索前后分别做缓存/指标/流式控制。</summary>
    /// <param name="question">用户问题。</param>
    /// <param name="topK">返回的相关 chunk 数,默认 4。</param>
    /// <param name="mode">检索模式:vector | hybrid(默认)| hybrid_rerank。</param>
    /// <returns>(答案文本, 来源列表)。</returns>
    public async Task<(string answer, List<Dictionary<string, object>> sources)> AskAsync(string question, int topK = 4, string mode = "hybrid")
    {
        var docs = await RetrieveAsync(question, topK, mode);
        var (answer, sources, _) = await GenerateAsync(question, docs);
        return (answer, sources);
    }

    /// <summary>基于已检索的 docs 生成答案(防幻觉 Prompt)。返回答案/来源/token 数。
    /// <para>防幻觉设计:system prompt 明确要求"只依据【上下文】回答、标注来源条目 id、
    /// 上下文没有就说没有、不要编造",把 RAG 的忠实性约束前置到提示词层。</para>
    /// <para>temperature=0.3:知识问答要稳定但不要逐字复读,取偏低温度。</para></summary>
    /// <param name="question">用户问题。</param>
    /// <param name="docs">检索命中的 chunk 列表(已按相关度排序)。</param>
    /// <returns>(答案, 去重后的来源列表, 本次 LLM 调用的 total_tokens)。</returns>
    public async Task<(string answer, List<Dictionary<string, object>> sources, int tokens)> GenerateAsync(string question, List<Chunk> docs)
    {
        var context = BuildContext(docs);
        // 原始字符串($"""):保留多行格式,上下文直接内嵌进 system prompt
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
        var (answer, usage) = await _llm.ChatAsync(messages, 0.3);
        // usage 字典里可能没有 token 统计(取决于供应商),缺省记 0
        var tokens = usage.TryGetValue("total_tokens", out var tk) && tk is int i ? i : 0;
        return (answer, ExtractSources(docs), tokens);
    }

    /// <summary>混合检索(对应 B 项目 retriever.py / C 项目 HybridRetriever.java)。
    /// 三种模式:
    /// <list type="bullet">
    /// <item>vector —— 纯向量:问题向量化后与当前租户全部 chunk 算余弦,取 topK。</item>
    /// <item>hybrid —— 向量 + BM25(简化词频)双路召回,RRF 融合排名取 topK。</item>
    /// <item>hybrid_rerank —— 在 hybrid 基础上,把融合结果前若干条交给 LLM 重排。</item>
    /// </list>
    /// 两路都先过采样到 vecK=max(topK*2, 8) 再融合:给融合层更大候选池,减少漏召回。</summary>
    /// <param name="query">查询文本(topic 名或自然语言问题)。</param>
    /// <param name="topK">最终返回条数。</param>
    /// <param name="mode">vector | hybrid | hybrid_rerank。</param>
    /// <returns>按相关度降序的 chunk 列表;库空时返回空列表。</returns>
    public async Task<List<Chunk>> RetrieveAsync(string query, int topK, string mode)
    {
        if (_store.Count == 0) return new();
        var tenant = TenantContext.CurrentTenant;
        var qEmb = (await _llm.EmbedAsync(new List<string> { query }))[0];
        var vecK = Math.Max(topK * 2, 8);
        // 向量检索:过滤当前租户(租户隔离读取侧)→ 余弦相似度降序 → 过采样 vecK 条
        var vec = _store.Where(e => tenant.Equals(e.chunk.Metadata.GetValueOrDefault("tenant_id")))
                       .Select(e => (e.chunk, score: Cosine(qEmb, e.emb)))
                       .OrderByDescending(x => x.score).Take(vecK).ToList();

        if (mode == "vector") return vec.Take(topK).Select(x => x.chunk).ToList();

        // BM25:只算当前租户的 chunk。
        // 分数说明:这里是 BM25 的简化版 —— 不算 IDF/k1/b,
        // tf = 查询各词元在该 chunk 中的出现次数之和,s = tf / chunk 词元总数(词频密度)。
        // 词频密度与向量分数足够互补(一个抓关键词精确命中,一个抓语义相近),
        // 融合层(RRF)只看排名不看绝对分,因此量纲差异不影响结果。
        var qTokens = Tokenize(query);
        // 取当前租户 chunk 在 _store 中的下标(BM25 分词表与 _store 同索引)
        var tenantIndices = _store.Select((e, i) => (e, i))
            .Where(x => tenant.Equals(x.e.chunk.Metadata.GetValueOrDefault("tenant_id")))
            .Select(x => x.i).ToList();
        var bm = new List<(int idx, double score)>();
        foreach (var i in tenantIndices)
        {
            var tf = qTokens.Sum(t => _bm25Tokens[i].Count(x => x == t));
            var s = _bm25Tokens[i].Count == 0 ? 0 : (double)tf / _bm25Tokens[i].Count;
            if (s > 0) bm.Add((i, s));
        }
        bm = bm.OrderByDescending(x => x.score).Take(vecK).ToList();

        // RRF(Reciprocal Rank Fusion):score = Σ 1/(60 + rank),常数 60 是 RRF 原文默认值。
        // 只依赖两路各自的"排名"而非绝对分数,天然规避余弦(0~1)与词频密度量纲不可比的问题;
        // 同一条 chunk 若两路都召回,会累加两次贡献,排名自然靠前。
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
        var fused = scores.OrderByDescending(x => x.Value).Select(x => docsByKey[x.Key]).ToList();
        if (mode == "hybrid_rerank")
            // 重排候选池取 max(topK*3, 10):比最终 topK 宽,给 LLM 纠偏空间又控制 prompt 长度
            return await LlmRerankAsync(query, fused.Take(Math.Max(topK * 3, 10)).ToList(), topK);
        return fused.Take(topK).ToList();
    }

    /// <summary>LLM 重排:让模型按与问题的相关度给候选排序(跨语言一致的 rerank 实现,
    /// 无需部署 cross-encoder 模型;失败则回退原 RRF 顺序)。
    /// <para>实现要点:候选正文截断到 200 字控制 prompt 长度;temperature=0 要求稳定排序;
    /// 强制模型只输出 {"order":[序号...]} 便于解析;解析后校验序号合法性、
    /// 补上模型漏掉的候选(保证不丢结果);任何异常(网络/非法 JSON/字段缺失)都回退原序,
    /// 重排是增强不是关键路径。</para></summary>
    /// <param name="query">原始问题。</param>
    /// <param name="candidates">RRF 融合后的候选 chunk(已按融合分降序)。</param>
    /// <param name="topK">重排后返回条数。</param>
    /// <returns>重排后的前 topK 个 chunk。</returns>
    private async Task<List<Chunk>> LlmRerankAsync(string query, List<Chunk> candidates, int topK)
    {
        if (candidates.Count <= 1) return candidates.Take(topK).ToList();
        // 拼编号候选清单:[0] 正文前 200 字 —— 编号即模型返回 order 里的序号
        var sb = new StringBuilder();
        for (int i = 0; i < candidates.Count; i++)
            sb.AppendLine($"[{i}] {(candidates[i].Text.Length > 200 ? candidates[i].Text[..200] : candidates[i].Text)}");
        var messages = new List<Dictionary<string, string>>
        {
            new() { ["role"] = "system", ["content"] = "你是检索结果重排器。按候选与【问题】的相关度从高到低排序,只输出 JSON:{\"order\":[片段序号,...]}。不要解释。" },
            new() { ["role"] = "user", ["content"] = $"问题:{query}\n候选:\n{sb}" },
        };
        try
        {
            var raw = await _llm.ChatJsonAsync(messages, 0.0);
            using var doc = JsonDocument.Parse(raw);
            var order = doc.RootElement.GetProperty("order").EnumerateArray().Select(x => x.GetInt32()).ToList();
            // 过滤越界序号,防止模型输出非法索引
            var reranked = order.Where(i => i >= 0 && i < candidates.Count).Select(i => candidates[i]).ToList();
            // 补上模型漏掉的候选,保证不丢结果
            foreach (var c in candidates) if (!reranked.Contains(c)) reranked.Add(c);
            return reranked.Take(topK).ToList();
        }
        catch
        {
            // 重排失败(LLM 故障/输出非 JSON 等):回退 RRF 原序,保证检索永远可用
            return candidates.Take(topK).ToList();
        }
    }

    /// <summary>流式生成(供 SSE):检索结果拼上下文 → 逐 token yield。
    /// <para>与 GenerateAsync 用同一套防幻觉 system prompt,区别在于走
    /// LlmClient.ChatStreamAsync 的 IAsyncEnumerable,Program.cs 的 /api/ask(stream=true)
    /// 把每个 token 包成 SSE data 帧即时 FlushAsync 给前端,实现打字机效果。</para></summary>
    /// <param name="question">用户问题。</param>
    /// <param name="docs">检索命中的 chunk。</param>
    /// <param name="ct">取消令牌;[EnumeratorCancellation] 让调用方传入的
    /// CancellationToken(端点用 ctx.RequestAborted)能正确传播进迭代器内部,
    /// 客户端断开时及时终止 LLM 流式调用。</param>
    public async System.Collections.Generic.IAsyncEnumerable<string> GenerateStreamAsync(
        string question, List<Chunk> docs,
        [System.Runtime.CompilerServices.EnumeratorCancellation] System.Threading.CancellationToken ct = default)
    {
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
        await foreach (var tok in _llm.ChatStreamAsync(messages, 0.3, ct))
            yield return tok;
    }

    /// <summary>供 SSE 端点先发来源事件用。</summary>
    /// <param name="docs">检索命中的 chunk。</param>
    /// <returns>去重后的来源列表(id/topic/card_type),SSE 的 retrieve 事件携带。</returns>
    public static List<Dictionary<string, object>> GetSources(List<Chunk> docs) => ExtractSources(docs);

    /// <summary>把检索结果拼成给 LLM 的【上下文】文本:
    /// 每条一行 "[序号] id=topic_id | 标题(卡片类型)",紧跟正文。
    /// 编号让模型能按 [1][2] 引用来源,与 system prompt 的"标注来源条目 id"呼应;
    /// 空结果返回 "(空)",让模型明确知道无上下文可依据。</summary>
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

    /// <summary>从检索结果提取来源列表(响应里的 sources 字段):
    /// 按 topic_id 去重、保持首次出现顺序 —— 同一 topic 的多个 chunk 命中只算一个来源。</summary>
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

    /// <summary>余弦相似度:dot(a,b) / (|a|·|b|)。手写而非引数值库 —— 一次循环同时算点积与模长;
    /// 任一向量为零向量时返回 0 防除零。</summary>
    private static double Cosine(float[] a, float[] b)
    {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.Length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.Sqrt(na) * Math.Sqrt(nb));
    }

    private static string Key(Chunk c) => c.Text;  // 用全文去重,避免前 N 字相同的 chunk 被误并

    /// <summary>轻量分词(BM25 用,不引分词库):
    /// 中文按单字切(unigram,'一'~'鿿' CJK 统一表意区)—— 免去分词器依赖,
    /// 对知识库规模下关键词命中足够;英文/数字连续片段聚成词并转小写;
    /// 其余字符(标点/空白)作分隔符。这样中英文混排的查询都能产出可比对的词元。</summary>
    private static List<string> Tokenize(string text)
    {
        var out_ = new List<string>();
        var buf = new StringBuilder();
        foreach (var ch in text)
        {
            if (ch >= '一' && ch <= '鿿')
            {
                // 中文字符:先 flush 缓冲区里的英文词,再单独成词元
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

/// <summary>面试问答服务:出题 + 评估。
/// <para>架构位置:支撑 /api/interview/question 与 /api/interview/evaluate 两个端点,
/// 也被 AgentService 的 ask/evaluate 节点复用。
/// 出题完全基于知识库预置的 recallPrompts(不调 LLM,快且可控);
/// 评估用 LLM-as-Judge,按 topic 预置 rubric(必答点/加分点/常见错误)客观打分。</para>
/// <para>对应关系:B 项目拆成 app/interview/question_gen.py + evaluator.py;
/// C 项目拆成 interview/QuestionService.java + EvaluatorService.java;D 项目合并为一个类。</para>
/// </summary>
public class InterviewService
{
    private readonly LlmClient _llm;
    private readonly KnowledgeBase _kb;

    public InterviewService(LlmClient llm, KnowledgeBase kb)
    {
        _llm = llm;
        _kb = kb;
    }

    /// <summary>从 topic 的 recallPrompts 出题(对应 B question_gen.py / C QuestionService.java)。
    /// 不调 LLM:题目是知识库预置的,保证质量可控、零延迟;难度过滤按 difficulty 精确匹配。</summary>
    /// <param name="topicId">topic id。</param>
    /// <param name="difficulty">可选难度过滤(1~5);null 表示不过滤。</param>
    /// <param name="count">期望题目数,实际返回 min(count, 可用题数)。</param>
    /// <returns>题目列表,每项含 question_id/prompt/difficulty。</returns>
    /// <exception cref="ArgumentException">topic 不存在;端点层捕获后返回 404。</exception>
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
            // question_id 缺省按 "{topicId}.recall.{序号}" 兜底生成,保证评估时能反解出 topicId
            var qid = p.TryGetProperty("id", out var id) ? id.GetString() ?? $"{topicId}.recall.{i + 1}" : $"{topicId}.recall.{i + 1}";
            var prompt = p.TryGetProperty("prompt", out var pr) ? pr.GetString() ?? "" : "";
            var diff = p.TryGetProperty("difficulty", out var df) ? df.GetInt32() : t.Difficulty;
            out_.Add(new Dictionary<string, object> { ["question_id"] = qid, ["prompt"] = prompt, ["difficulty"] = diff });
        }
        return out_;
    }

    /// <summary>LLM-as-Judge 评估候选人回答(对应 B evaluator.py / C EvaluatorService.java)。
    /// 流程:由 questionId 反解 topicId → 校验 rubric.mustHave 存在 →
    /// 把 rubric(必答点/加分点/常见错误)与输出 JSON 格式写进 system prompt →
    /// temperature=0 调 LLM(评估要可复现)→ 解析 JSON。
    /// 降级策略:LLM 调用失败或输出非合法 JSON 时,返回 degraded=true 的零分结果,
    /// 端点依然 200 —— 评估是辅助能力,不能因模型抖动让整个面试流程挂掉。</summary>
    /// <param name="questionId">题目 id(形如 topicId.recall.N)。</param>
    /// <param name="userAnswer">候选人回答原文。</param>
    /// <returns>评估结果:score(0-100)/hit(命中点)/missed(遗漏点)/mistakes(常见错误)/feedback/degraded。</returns>
    /// <exception cref="ArgumentException">topic 不存在或缺少 rubric.mustHave;端点层捕获后返回 404。</exception>
    public async Task<Dictionary<string, object>> EvaluateAsync(string questionId, string userAnswer)
    {
        var topicId = ExtractTopicId(questionId);
        var t = _kb.Get(topicId) ?? throw new ArgumentException($"topic 不存在:{topicId}");
        // rubric.mustHave 是评估的锚点,缺失则评估无意义,直接报错(端点转 404)
        if (t.Rubric.ValueKind == JsonValueKind.Undefined || !t.Rubric.TryGetProperty("mustHave", out _))
            throw new ArgumentException($"topic 缺少 rubric.mustHave:{topicId}");

        // 评分标准直接内嵌进 system prompt,并强制输出 JSON 结构(与 B/C 的评估提示词同构)
        var system = "你是资深技术面试官,按给定评分标准客观评估,输出严格 JSON。\n" +
            "评分标准:\n" +
            "- 必答点(must_have):" + t.Rubric.GetProperty("mustHave") + "\n" +
            "- 加分点(good_to_have):" + t.Rubric.GetProperty("goodToHave") + "\n" +
            "- 常见错误(common_mistakes):" + t.Rubric.GetProperty("commonMistakes") + "\n" +
            "输出 JSON:{\"score\":0-100,\"hit_points\":[],\"missed\":[],\"mistakes\":[],\"feedback\":\"\"}";
        // 按 questionId 找回题目原文,让评审模型看到题面(只有回答没法判偏题与否)
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
        // temperature=0:同一回答多次评估结果应一致(可复现)
        try { content = await _llm.ChatJsonAsync(messages, 0.0); }
        catch (Exception e) { return Degraded($"评估服务不可用:{e.Message}"); }
        try
        {
            // 把 LLM 输出的 JSON 拍平成字典(字段名与 B/C 的评估响应对齐)
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

    /// <summary>降级评估结果:零分 + 空列表 + degraded=true,feedback 说明降级原因。
    /// 调用方(端点/AgentService)可据此向前端表达"评估暂不可用"而非报错。</summary>
    private static Dictionary<string, object> Degraded(string feedback) => new()
    {
        ["score"] = 0, ["hit"] = new List<string>(), ["missed"] = new List<string>(),
        ["mistakes"] = new List<string>(), ["feedback"] = feedback, ["degraded"] = true,
    };

    /// <summary>从 questionId 反解 topicId。约定:题目 id 形如 "&lt;topicId&gt;.recall.&lt;n&gt;"
    /// (如 java.concurrency.volatile.recall.1)→ 去掉末尾两段得 topicId;
    /// 不符合约定时退化为去掉最后一段;无点号则原样返回。
    /// 静态公开是因为 AgentPlugin.GetScoringRubric 也要用它。</summary>
    public static string ExtractTopicId(string questionId)
    {
        var parts = questionId.Split('.');
        if (parts.Length >= 3 && parts[^2] == "recall") return string.Join(".", parts[..^2]);
        return questionId.Contains('.') ? questionId[..questionId.LastIndexOf('.')] : questionId;
    }
}
