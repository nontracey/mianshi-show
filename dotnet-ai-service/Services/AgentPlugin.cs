using System.ComponentModel;
using Microsoft.SemanticKernel;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>Agent 工具插件(与 B/C 的 tools.py/AgentTools.java 对应)。
/// 用 Semantic Kernel 的 [KernelFunction] 特性把普通 C# 方法暴露成模型可调用的工具,
/// 注册到 Kernel 后由 FunctionChoiceBehavior.Auto() 让模型自动决定调用。
/// <p>search_knowledge 内部把检索结果存 AsyncLocal,供 AgentService 在 LLM 调用后取回 docs。 </summary>
public class AgentPlugin
{
    private readonly RagService _rag;
    private readonly KnowledgeBase _kb;
    private readonly List<string> _notes = new();
    private readonly object _notesLock = new();

    /** AsyncLocal:LLM 调 search_knowledge 后,把 docs 存这里供编排器取回(同异步上下文)。 */
    private static readonly AsyncLocal<List<Chunk>?> LastRetrieved = new();

    public AgentPlugin(RagService rag, KnowledgeBase kb)
    {
        _rag = rag;
        _kb = kb;
    }

    [KernelFunction]
    [Description("检索面试知识库,返回与 query 相关的知识条目。用于出题前了解该 topic 的知识脉络。")]
    public async Task<List<Dictionary<string, object>>> SearchKnowledge(
        [Description("检索查询,如 topic id 或关键词")] string query,
        [Description("返回条数,默认 4")] int topK = 4)
    {
        var docs = await _rag.RetrieveAsync(query, topK, "hybrid");
        LastRetrieved.Value = docs;
        return docs.Take(topK).Select(d => new Dictionary<string, object>
        {
            ["topic_id"] = d.Metadata.GetValueOrDefault("topic_id") ?? "",
            ["title"] = d.Metadata.GetValueOrDefault("title") ?? "",
            ["text"] = d.Text.Length > 200 ? d.Text[..200] : d.Text,
        }).ToList();
    }

    [KernelFunction]
    [Description("查某道题的评分标准(must_have/good_to_have/common_mistakes)。用于评估前明确要点。")]
    public Dictionary<string, object> GetScoringRubric(
        [Description("题目 id,如 java.concurrency.volatile.recall.1")] string questionId)
    {
        var topicId = InterviewService.ExtractTopicId(questionId);
        var t = _kb.Get(topicId);
        if (t == null) return new() { ["error"] = "topic 不存在:" + topicId };
        return new()
        {
            ["topic_id"] = t.Id,
            ["title"] = t.Title,
            ["rubric"] = t.Rubric.ToString()
        };
    }

    [KernelFunction]
    [Description("记一条学习笔记(如评估反馈、学习建议)。演示用,存内存。")]
    public Dictionary<string, object> SaveNote(
        [Description("笔记内容")] string text)
    {
        lock (_notesLock) { _notes.Add(text); }
        return new() { ["saved"] = true, ["length"] = text.Length, ["total"] = _notes.Count };
    }

    public static List<Chunk>? GetLastRetrieved() => LastRetrieved.Value;
    public static void ClearLastRetrieved() => LastRetrieved.Value = null;
}
