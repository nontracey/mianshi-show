using System.Text.Json;
using DotnetAiService.Common;

namespace DotnetAiService.Services;

/// <summary>Agent 编排(与 B/C 同构):retrieve -> ask -> simulate -> evaluate -> (followup) -> advise。
/// 简化版:返回事件列表(JSON);SSE 流式留后续。</summary>
public class AgentService
{
    private readonly RagService _rag;
    private readonly InterviewService _interview;
    private readonly LlmClient _llm;
    private readonly KnowledgeBase _kb;

    public AgentService(RagService rag, InterviewService interview, LlmClient llm, KnowledgeBase kb)
    {
        _rag = rag;
        _interview = interview;
        _llm = llm;
        _kb = kb;
    }

    public async Task<List<Dictionary<string, object>>> RunAsync(string topic, int rounds)
    {
        var events = new List<Dictionary<string, object>>();
        rounds = Math.Max(1, rounds);

        // 1. retrieve(工具:search_knowledge)
        var docs = await _rag.RetrieveAsync(topic, 4, "hybrid");
        events.Add(new()
        {
            ["type"] = "retrieve",
            ["payload"] = new Dictionary<string, object>
            {
                ["tool_call"] = "search_knowledge",
                ["docs_count"] = docs.Count,
                ["docs"] = docs.Take(3).Select(d => new Dictionary<string, object>
                {
                    ["topic_id"] = d.Metadata.GetValueOrDefault("topic_id") ?? "",
                    ["title"] = d.Metadata.GetValueOrDefault("title") ?? "",
                }).ToList(),
            },
        });

        int round = 0;
        Dictionary<string, object>? lastEval = null;
        while (round < rounds)
        {
            round++;
            // 2. ask
            var qs = _interview.GenerateQuestions(topic, null, 1);
            if (qs.Count == 0)
            {
                events.Add(new() { ["type"] = "error", ["payload"] = new { msg = "topic 无 recallPrompts:" + topic } });
                return events;
            }
            var q = qs[0];
            events.Add(new()
            {
                ["type"] = "question",
                ["payload"] = new Dictionary<string, object>
                {
                    ["round"] = round, ["question_id"] = q["question_id"],
                    ["prompt"] = q["prompt"], ["difficulty"] = q["difficulty"],
                },
            });

            // 3. simulate
            string answer;
            try
            {
                var sys = $"你是有 3 年经验的中级工程师,正在面试。用第一人称回答(可有遗漏,别瞎编):\n题目:{q["prompt"]}";
                var messages = new List<Dictionary<string, string>>
                {
                    new() { ["role"] = "system", ["content"] = sys },
                    new() { ["role"] = "user", ["content"] = "请回答。" },
                };
                (answer, _) = await _llm.ChatAsync(messages, 0.5);
            }
            catch (Exception e)
            {
                answer = "(模拟回答失败:" + e.Message + ")";
            }
            events.Add(new() { ["type"] = "answer", ["payload"] = new { text = answer, round } });

            // 4. evaluate
            try
            {
                lastEval = await _interview.EvaluateAsync(q["question_id"].ToString()!, answer);
            }
            catch (Exception e)
            {
                events.Add(new() { ["type"] = "error", ["payload"] = new { msg = "评估失败:" + e.Message } });
                return events;
            }
            events.Add(new() { ["type"] = "evaluate", ["payload"] = lastEval });

            // 5. decide
            var score = Convert.ToInt32(lastEval["score"]);
            if (score < 70 && round < rounds)
            {
                events.Add(new() { ["type"] = "followup", ["payload"] = new { round, reason = $"score={score} < 70,继续追问" } });
                continue;
            }
            break;
        }

        // 6. advise
        if (lastEval != null)
        {
            string advice;
            try
            {
                var sys = $"你是面试教练。基于评估给 3 条学习建议。\n评估:{JsonSerializer.Serialize(lastEval)}";
                var messages = new List<Dictionary<string, string>>
                {
                    new() { ["role"] = "system", ["content"] = sys },
                    new() { ["role"] = "user", ["content"] = "请给建议。" },
                };
                (advice, _) = await _llm.ChatAsync(messages, 0.3);
            }
            catch (Exception e)
            {
                advice = "(建议生成失败:" + e.Message + ")";
            }
            events.Add(new() { ["type"] = "advise", ["payload"] = new { advice } });
        }

        events.Add(new() { ["type"] = "done", ["payload"] = new { rounds_done = round } });
        return events;
    }
}
