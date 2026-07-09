using System.Text.RegularExpressions;

namespace DotnetAiService.Services;

/// <summary>输入护栏:提示注入检测 + PII 脱敏(与 B/C 同思路)。</summary>
public static class Guardrails
{
    private static readonly string[] InjectionMarkers =
    {
        "忽略以上", "忽略之前", "忽略前面", "ignore previous", "ignore above",
        "disregard the", "system prompt", "你现在是", "扮演", "jailbreak",
    };

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
    public static string RedactPii(string s)
    {
        if (string.IsNullOrEmpty(s)) return s;
        s = Regex.Replace(s, @"1[3-9]\d{9}", "[手机]");
        s = Regex.Replace(s, @"[\w.+-]+@[\w-]+\.[\w.-]+", "[邮箱]");
        s = Regex.Replace(s, @"\b\d{15,19}\b", "[卡号]");
        return s;
    }
}
