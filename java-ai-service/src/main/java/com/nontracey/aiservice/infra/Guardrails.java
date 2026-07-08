package com.nontracey.aiservice.infra;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 护栏:输入注入检测 + PII 脱敏(与 B 同策略)。 */
@Component
public class Guardrails {

    private static final Pattern[] INJECTION = {
            Pattern.compile("忽略.{0,10}(以上|前面|上面).{0,10}(指令|规则|提示)"),
            Pattern.compile("(?i)ignore.{0,10}(above|previous|prior).{0,10}(instruction|rule|prompt)"),
            Pattern.compile("(?i)(system|admin|root)\\s*[:：]\\s*"),
            Pattern.compile("<\\|im_start\\|>|<\\|system\\|>")
    };

    private static final Pattern[] PII = {
            Pattern.compile("1[3-9]\\d{9}"),
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            Pattern.compile("\\b\\d{15}(?:\\d{2}[\\dXx])?\\b")
    };

    public record GuardResult(boolean blocked, String reason) {}

    public GuardResult checkInjection(String text) {
        if (text == null || text.isBlank()) return new GuardResult(false, "");
        if (text.length() > 4000) return new GuardResult(true, "输入超长");
        for (Pattern p : INJECTION) {
            if (p.matcher(text).find()) return new GuardResult(true, "疑似 prompt 注入");
        }
        return new GuardResult(false, "");
    }

    public String redactPii(String text) {
        if (text == null) return null;
        for (Pattern p : PII) text = p.matcher(text).replaceAll("[PII]");
        return text;
    }
}
