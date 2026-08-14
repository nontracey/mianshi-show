using System.Text.Json;
using DotnetAiService.Common;
using Microsoft.SemanticKernel;
using Microsoft.SemanticKernel.Connectors.OpenAI;
using System.Runtime.CompilerServices;
using System.Threading.Channels;

namespace DotnetAiService.Services;

/// <summary>Agent 编排(与 B/C 同构):retrieve -> ask -> simulate -> evaluate -> (followup) -> advise。
/// <p>retrieve/advise 节点用 Semantic Kernel 的 [KernelFunction] + FunctionChoiceBehavior.Auto()
/// 让 LLM 通过 Function Calling 调用 search_knowledge/save_note 工具;
/// ask/simulate/evaluate 走显式编排(流程固定)。
/// <p>简化版:返回事件列表(JSON);SSE 流式留后续。
/// <p>架构位置:对应 B 项目 app/agent/graph.py + state.py(LangGraph 状态机)、
/// C 项目 agent/AgentOrchestrator.java。三语言状态机节点一一对应:
/// retrieve(检索知识)→ ask(出题)→ simulate(模拟候选人作答)→ evaluate(LLM 评分)
/// → decide(分数 &lt; 70 且未达轮次则 followup 追问,否则跳出)→ advise(学习建议并存档)。
/// <p>关键设计点 —— "混合编排":流程骨架固定(面试流程不该让模型自由发挥),
/// 但在 retrieve/advise 两个节点把"是否调工具、怎么调"交给 LLM 自主决策
/// (FunctionChoiceBehavior.Auto),借此演示 SK 的 Function Calling 能力;
/// 工具调用结果通过 AgentPlugin 的 AsyncLocal 副作用通道取回。
/// 工具没被调用/失败时自动降级为显式检索,保证流程永远能走完。</p>
/// </summary>
public class AgentService
{
    private readonly Kernel _kernel;
    private readonly AgentPlugin _plugin;
    private readonly RagService _rag;
    private readonly InterviewService _interview;
    private readonly LlmClient _llm;
    private readonly KnowledgeBase _kb;

    /// <summary>构造:注入 SK Kernel(Function Calling 执行器)与各业务服务,
    /// 并把 AgentPlugin 注册为名为 "Agent" 的插件(带重复注册检查,
    /// 避免 singleton 重建等场景下插件重复挂载)。</summary>
    public AgentService(Kernel kernel, AgentPlugin plugin, RagService rag,
                        InterviewService interview, LlmClient llm, KnowledgeBase kb)
    {
        _kernel = kernel;
        _plugin = plugin;
        _rag = rag;
        _interview = interview;
        _llm = llm;
        _kb = kb;
        // 注册 AgentPlugin 到 Kernel(带检查避免重复注册)
        if (!kernel.Plugins.Contains("Agent"))
            kernel.Plugins.AddFromObject(plugin, "Agent");
    }

    /// <summary>跑一场完整的模拟面试,返回按时间顺序的事件列表。
    /// 事件类型:retrieve(检索结果)/ question(出题)/ answer(模拟回答)/
    /// evaluate(评分)/ followup(追问决定)/ advise(学习建议)/ error / done。
    /// 对应 /api/agent/session 端点;B 项目同等流程经 SSE 逐事件推送,D 简化为一次性返回。</summary>
    /// <param name="topic">面试主题(topic id 或关键词,用于检索与出题)。</param>
    /// <param name="rounds">期望面试轮数,内部兜底至少 1;分数达标会提前结束。</param>
    /// <returns>事件列表,每个事件为 {type, payload} 字典。</returns>
    public async Task<List<Dictionary<string, object>>> RunAsync(
        string topic, int rounds, CancellationToken ct = default,
        Func<Dictionary<string, object>, ValueTask>? onEvent = null)
    {
        var events = new List<Dictionary<string, object>>();
        async ValueTask Emit(Dictionary<string, object> evt)
        {
            events.Add(evt);
            if (onEvent != null) await onEvent(evt);
        }
        rounds = Math.Max(1, rounds);
        ct.ThrowIfCancellationRequested();

        // ---------- 1. retrieve(LLM 调 search_knowledge 工具,通过 FunctionChoiceBehavior.Auto) ----------
        // 先清空 AsyncLocal 残留,确保后面取到的是本次调用写入的检索结果
        AgentPlugin.ClearLastRetrieved();
        // prompt 明确"指示"模型调用工具;是否真调用由模型决策(Auto 模式)
        var retrievePrompt = $"你是技术面试官。请调用 search_knowledge 工具检索 topic:{topic} 的知识(query={topic}, topK=4),了解重点后再出题。";
        var retrieveSettings = new OpenAIPromptExecutionSettings
        {
            Temperature = 0,
            FunctionChoiceBehavior = FunctionChoiceBehavior.Auto()   // 让模型自主决定调用 search_knowledge
        };
        try
        {
            await _kernel.InvokePromptAsync(retrievePrompt, new(retrieveSettings), cancellationToken: ct);
        }
        catch (Exception)
        {
            // LLM 没调工具或调用失败,降级显式检索
        }
        // 工具调用的返回值不回传给编排器,只能从 AsyncLocal 副作用通道取
        var docs = AgentPlugin.GetLastRetrieved();
        if (docs == null || docs.Count == 0)
        {
            // 兜底:Function Calling 没生效时直接显式检索,保证后续节点有知识可用
            docs = await _rag.RetrieveAsync(topic, 4, "hybrid");
        }
        var docsForEvents = docs;
        await Emit(new()
        {
            ["type"] = "retrieve",
            ["payload"] = new Dictionary<string, object>
            {
                ["tool_call"] = "search_knowledge",
                ["docs_count"] = docsForEvents.Count,
                // 事件里只带前 3 条摘要,控制响应体积
                ["docs"] = docsForEvents.Take(3).Select(d => new Dictionary<string, object>
                {
                    ["topic_id"] = d.Metadata.GetValueOrDefault("topic_id") ?? "",
                    ["title"] = d.Metadata.GetValueOrDefault("title") ?? "",
                }).ToList(),
            },
        });

        int round = 0;
        Dictionary<string, object>? lastEval = null;
        // ---------- 多轮问答循环:ask → simulate → evaluate → decide ----------
        while (round < rounds)
        {
            round++;
            // ---------- 2. ask(显式编排:出题流程固定,不需要模型决策) ----------
            var qs = _interview.GenerateQuestions(topic, null, 1);
            if (qs.Count == 0)
            {
                // topic 无预置题:记 error 事件并终止整场(无法继续面试)
                await Emit(new() { ["type"] = "error", ["payload"] = new { msg = "topic 无 recallPrompts:" + topic } });
                return events;
            }
            var q = qs[0];
            await Emit(new()
            {
                ["type"] = "question",
                ["payload"] = new Dictionary<string, object>
                {
                    ["round"] = round, ["question_id"] = q["question_id"],
                    ["prompt"] = q["prompt"], ["difficulty"] = q["difficulty"],
                },
            });

            // ---------- 3. simulate(LLM 扮演候选人:没有真实用户时的自测闭环) ----------
            string answer;
            try
            {
                // 角色设定:中级工程师、第一人称、允许有遗漏但不编造 —— 模拟真实候选人的不完美回答
                var sys = $"你是有 3 年经验的中级工程师,正在面试。用第一人称回答(可有遗漏,别瞎编):\n题目:{q["prompt"]}";
                var messages = new List<Dictionary<string, string>>
                {
                    new() { ["role"] = "system", ["content"] = sys },
                    new() { ["role"] = "user", ["content"] = "请回答。" },
                };
                // temperature=0.5:回答要有点随机性,避免每轮一模一样的答案
                (answer, _) = await _llm.ChatAsync(messages, 0.5, ct);
            }
            catch (Exception e)
            {
                // 模拟回答失败不阻断流程:用占位文本继续,让评估环节暴露问题
                answer = "(模拟回答失败:" + e.Message + ")";
            }
            await Emit(new() { ["type"] = "answer", ["payload"] = new { text = answer, round } });

            // ---------- 4. evaluate(LLM-as-Judge,见 InterviewService.EvaluateAsync) ----------
            try
            {
                lastEval = await _interview.EvaluateAsync(q["question_id"].ToString()!, answer);
            }
            catch (Exception e)
            {
                // 评估抛出(topic 缺 rubric 等):记 error 事件并终止整场
                await Emit(new() { ["type"] = "error", ["payload"] = new { msg = "评估失败:" + e.Message } });
                return events;
            }
            await Emit(new() { ["type"] = "evaluate", ["payload"] = lastEval });

            // ---------- 5. decide(状态机分支:分数驱动是否追问) ----------
            var score = Convert.ToInt32(lastEval["score"]);
            if (score < 70 && round < rounds)
            {
                // 不及格(阈值 70,与 B/C 一致)且还有轮次配额 → followup 继续追问
                await Emit(new() { ["type"] = "followup", ["payload"] = new { round, reason = $"score={score} < 70,继续追问" } });
                continue;
            }
            break;  // 分数达标或轮次用完 → 进入 advise 收尾
        }

        // ---------- 6. advise(LLM 调 save_note 工具,通过 FunctionChoiceBehavior.Auto) ----------
        if (lastEval != null)
        {
            string advice;
            try
            {
                // 把评估 JSON 原文交给模型,prompt 同时要求:① 给 3 条针对性建议 ② 调用 save_note 存档
                var evalJson = JsonSerializer.Serialize(lastEval);
                var advisePrompt = $"你是面试教练。基于评估给 3 条学习建议,补足 missed。\n评估:{evalJson}\n给完建议后,调用 save_note 工具把建议原文保存(text=建议全文)。";
                var adviseSettings = new OpenAIPromptExecutionSettings
                {
                    Temperature = 0.3,
                    FunctionChoiceBehavior = FunctionChoiceBehavior.Auto()
                };
                var result = await _kernel.InvokePromptAsync(advisePrompt, new(adviseSettings), cancellationToken: ct);
                // 最终回复文本 = 建议正文(save_note 的调用发生在工具回合,不影响最终文本)
                advice = result.GetValue<string>() ?? "";
            }
            catch (Exception e)
            {
                // 建议生成失败不阻断:占位文本,done 事件照常发出
                advice = "(建议生成失败:" + e.Message + ")";
            }
            await Emit(new() { ["type"] = "advise", ["payload"] = new { advice } });
        }

        // 终态事件:告知前端流程正常走完及实际轮数
        await Emit(new() { ["type"] = "done", ["payload"] = new { rounds_done = round } });
        return events;
    }

    public async IAsyncEnumerable<Dictionary<string, object>> RunStreamAsync(
        string topic, int rounds, [EnumeratorCancellation] CancellationToken ct = default)
    {
        var channel = Channel.CreateBounded<Dictionary<string, object>>(
            new BoundedChannelOptions(16) {
                SingleReader = true, SingleWriter = true,
                FullMode = BoundedChannelFullMode.Wait
            });
        var worker = Task.Run(async () =>
        {
            try
            {
                await RunAsync(topic, rounds, ct, evt => channel.Writer.WriteAsync(evt, ct));
                channel.Writer.TryComplete();
            }
            catch (Exception error) { channel.Writer.TryComplete(error); }
        }, ct);
        await foreach (var evt in channel.Reader.ReadAllAsync(ct)) yield return evt;
        await worker;
    }
}
