package com.nontracey.aiservice.infra;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 安全护栏:输入 prompt 注入检测 + PII(个人敏感信息)脱敏(与 B 同策略)。
 *
 * <p><b>架构位置</b>:infra 层横切能力。RagController(/api/ask)与 InterviewController(/api/interview/evaluate)
 * 在把用户输入交给 LLM 之前先过这里,降低注入与隐私泄露风险。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/infra/guardrails.py}。
 */
@Component
public class Guardrails {

    /** prompt 注入特征正则:中英文"忽略以上指令"类话术、伪造 system/admin 前缀、特殊控制标记。 */
    private static final Pattern[] INJECTION = {
            Pattern.compile("忽略.{0,10}(以上|前面|上面).{0,10}(指令|规则|提示)"),
            Pattern.compile("(?i)ignore.{0,10}(above|previous|prior).{0,10}(instruction|rule|prompt)"),
            Pattern.compile("(?i)(system|admin|root)\\s*[:：]\\s*"),
            Pattern.compile("<\\|im_start\\|>|<\\|system\\|>")
    };

    /** PII 正则:手机号、邮箱、身份证号;命中后替换为 [PII]。 */
    private static final Pattern[] PII = {
            Pattern.compile("1[3-9]\\d{9}"),
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            Pattern.compile("\\b\\d{15}(?:\\d{2}[\\dXx])?\\b")
    };

    /**
     * 护栏检查结果。
     *
     * @param blocked 是否拦截
     * @param reason  拦截原因(未拦截时为空串)
     */
    public record GuardResult(boolean blocked, String reason) {}

    /**
     * 检测输入是否为可疑 prompt 注入。
     *
     * <p>规则:空输入放行;超过 4000 字直接拦截(防超长滥用);命中任一注入特征则拦截。
     *
     * @param text 用户输入
     * @return 检查结果
     */
    public GuardResult checkInjection(String text) {
        if (text == null || text.isBlank()) return new GuardResult(false, "");
        // 超长输入直接拒绝,避免 token 滥用与潜在攻击面
        if (text.length() > 4000) return new GuardResult(true, "输入超长");
        for (Pattern p : INJECTION) {
            if (p.matcher(text).find()) return new GuardResult(true, "疑似 prompt 注入");
        }
        return new GuardResult(false, "");
    }

    /**
     * 对文本做 PII 脱敏,把手机号/邮箱/身份证号替换为 [PII]。
     *
     * <p>主要用于日志打印,避免把用户敏感信息写入日志。
     *
     * @param text 原始文本
     * @return 脱敏后文本;入参为 null 时原样返回 null
     */
    public String redactPii(String text) {
        if (text == null) return null;
        for (Pattern p : PII) text = p.matcher(text).replaceAll("[PII]");
        return text;
    }
}
