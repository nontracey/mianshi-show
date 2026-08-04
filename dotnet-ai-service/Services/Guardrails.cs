using System.Text.RegularExpressions;

namespace DotnetAiService.Services;

/// <summary>输入护栏:提示注入检测 + PII 脱敏(与 B/C 同思路)。
/// <para>架构位置:所有用户输入进入 LLM 前的第一道防线。
/// /api/ask 端点在语义缓存之前就调用 DetectInjection(被拒的请求连 Embedding 都不调,
/// 把恶意输入的成本压到最低)。对应 B 项目 app/infra/guardrails.py、C 项目 infra/Guardrails.java。</para>
/// <para>检测思路:规则/黑名单先行(快、零成本、可解释),不引分类模型 ——
/// 命中注入特征词(中英文)直接拒绝;长度上限防超大输入撑爆上下文/刷 token。
/// 这是演示级护栏,生产应叠加模型分类、输出过滤等多层防御。</para>
/// </summary>
public static class Guardrails
{
    /// <summary>提示注入特征词(中英文混合):覆盖"忽略之前指令"类越狱话术、
    /// 套取 system prompt、角色扮演劫持等常见攻击模式。匹配时双方都转小写。</summary>
    private static readonly string[] InjectionMarkers =
    {
        "忽略以上", "忽略之前", "忽略前面", "ignore previous", "ignore above",
        "disregard the", "system prompt", "你现在是", "扮演", "jailbreak",
    };

    /// <summary>提示注入/异常输入检测(/api/ask 入口调用)。
    /// 规则按代价从低到高依次判定:空输入 → 超长(&gt;2000 字符)→ 注入特征词包含匹配。</summary>
    /// <param name="q">用户原始问题。</param>
    /// <returns>(是否拦截, 原因)。Blocked=true 时端点直接返回 400,不进任何 LLM 调用。</returns>
    public static (bool Blocked, string Reason) DetectInjection(string q)
    {
        if (string.IsNullOrWhiteSpace(q)) return (true, "空输入");
        if (q.Length > 2000) return (true, "输入超长(>2000)");
        var low = q.ToLowerInvariant();
        foreach (var m in InjectionMarkers)
            if (low.Contains(m.ToLowerInvariant()))
                return (true, $"疑似提示注入:命中「{m}」");
        return (false, "");
    }

    /// <summary>手机号/邮箱脱敏后再进日志。</summary>
    /// <param name="s">待脱敏文本。</param>
    /// <returns>敏感信息替换为 [手机]/[邮箱]/[卡号] 占位符的文本;null/空原样返回。</returns>
    public static string RedactPii(string s)
    {
        if (string.IsNullOrEmpty(s)) return s;
        // 中国大陆手机号:1[3-9] 开头共 11 位
        s = Regex.Replace(s, @"1[3-9]\d{9}", "[手机]");
        s = Regex.Replace(s, @"[\w.+-]+@[\w-]+\.[\w.-]+", "[邮箱]");
        // 15~19 位连续数字:银行卡号常见长度
        s = Regex.Replace(s, @"\b\d{15,19}\b", "[卡号]");
        return s;
    }
}
