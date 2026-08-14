using System.ComponentModel;
using Microsoft.SemanticKernel;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>Agent 工具插件(与 B/C 的 tools.py/AgentTools.java 对应)。
/// 用 Semantic Kernel 的 [KernelFunction] 特性把普通 C# 方法暴露成模型可调用的工具,
/// 注册到 Kernel 后由 FunctionChoiceBehavior.Auto() 让模型自动决定调用。
/// <p>search_knowledge 内部把检索结果存 AsyncLocal,供 AgentService 在 LLM 调用后取回 docs。
/// <p>架构位置:对应 B 项目 app/agent/tools.py(LangChain @tool)、
/// C 项目 agent/AgentTools.java(Spring AI ToolCallback)。
/// 三个工具:search_knowledge(知识检索)、get_scoring_rubric(评分标准查询)、save_note(笔记存档)。
/// <p>关键设计点:
/// ① 方法上的 [Description] 与参数 [Description] 会被 SK 编译进工具的 JSON Schema,
/// 模型靠这些描述决定"何时调、传什么参" —— 描述质量直接决定 Function Calling 成功率;
/// ② SK 的工具调用结果只回传给模型(作为下一轮上下文),不回传给编排器,
/// 所以 SearchKnowledge 额外把 docs 写入 AsyncLocal 副作用通道,AgentService 事后取回;
/// ③ 返回给模型的字典都做了截断/精简(正文 200 字),控制工具回合的 prompt 体积。</summary>
/// </summary>
public class AgentPlugin
{
    private readonly RagService _rag;
    private readonly KnowledgeBase _kb;
    /// <summary>学习笔记列表(save_note 写入)。演示用途存内存即可;生产应落库。</summary>
    private readonly List<string> _notes = new();
    /// <summary>_notes 的并发写保护(save_note 可能被不同请求并发调用)。</summary>
    private readonly object _notesLock = new();

    /** AsyncLocal:LLM 调 search_knowledge 后,把 docs 存这里供编排器取回(同异步上下文)。 */
    /// <remarks>为什么用 AsyncLocal:工具执行与 AgentService.RunAsync 处于同一异步调用链
    /// (InvokePromptAsync 内部触发),AsyncLocal 值沿 ExecutionContext 传递,
    /// 编排器在 await 之后依然能取到;静态成员,故配套提供 Clear/Get 静态方法。
    /// 类似 Java 的 ThreadLocal,但跨 await 正确。</remarks>
    private static readonly AsyncLocal<List<Chunk>?> LastRetrieved = new();

    public AgentPlugin(RagService rag, KnowledgeBase kb)
    {
        _rag = rag;
        _kb = kb;
    }

    /// <summary>工具一:检索面试知识库(混合检索 hybrid 模式)。
    /// 这是 Agent"先查资料再出题/答题"能力的来源,也是三语言都有的核心工具。</summary>
    /// <param name="query">检索查询(topic id 或关键词)。</param>
    /// <param name="topK">返回条数,默认 4。</param>
    /// <returns>精简后的知识条目列表(topic_id/title/截断正文),供模型阅读。</returns>
    [KernelFunction]
    [Description("检索面试知识库,返回与 query 相关的知识条目。用于出题前了解该 topic 的知识脉络。")]
    public async Task<List<Dictionary<string, object>>> SearchKnowledge(
        [Description("检索查询,如 topic id 或关键词")] string query,
        [Description("返回条数,默认 4")] int topK = 4)
    {
        var docs = await _rag.RetrieveAsync(query, topK, "hybrid");
        // 副作用通道:完整 docs(含向量库 chunk)存 AsyncLocal,编排器用 GetLastRetrieved 取
        LastRetrieved.Value = docs;
        // 返回给模型的是精简版:正文截断 200 字,避免工具结果撑爆上下文
        return docs.Take(topK).Select(d => new Dictionary<string, object>
        {
            ["topic_id"] = d.Metadata.GetValueOrDefault("topic_id") ?? "",
            ["title"] = d.Metadata.GetValueOrDefault("title") ?? "",
            ["text"] = d.Text.Length > 200 ? d.Text[..200] : d.Text,
        }).ToList();
    }

    /// <summary>工具二:查某道题的评分标准。评估前让模型先明确必答点/加分点/常见错误,
    /// 使打分更贴合 rubric(与 B/C 的同名工具对应)。</summary>
    /// <param name="questionId">题目 id,如 java.concurrency.volatile.recall.1
    /// (内部按 InterviewService.ExtractTopicId 反解出 topicId)。</param>
    /// <returns>topic_id/title/rubric 原文;topic 不存在时返回 {error} 字典(不抛异常,
    /// 让模型看到错误信息自行处理,这是 Function Calling 的容错惯例)。</returns>
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

    /// <summary>工具三:记一条学习笔记。advise 节点让模型"给完建议后存档",
    /// 演示 Function Calling 的写操作闭环;内存存储,重启即失(演示定位)。</summary>
    /// <param name="text">笔记内容(一般为学习建议全文)。</param>
    /// <returns>{saved, length, total}:让模型确认写入成功及当前笔记总数。</returns>
    [KernelFunction]
    [Description("记一条学习笔记(如评估反馈、学习建议)。演示用,存内存。")]
    public Dictionary<string, object> SaveNote(
        [Description("笔记内容")] string text)
    {
        lock (_notesLock) { _notes.Add(text); }
        return new() { ["saved"] = true, ["length"] = text.Length, ["total"] = _notes.Count };
    }

    /// <summary>编排器入口:取本次调用链中 search_knowledge 写入的完整检索结果(可能为 null)。</summary>
    public static List<Chunk>? GetLastRetrieved() => LastRetrieved.Value;

    /// <summary>编排器入口:新一轮 Agent 运行前清空残留,避免取到上一次的结果。</summary>
    public static void ClearLastRetrieved() => LastRetrieved.Value = null;
}
