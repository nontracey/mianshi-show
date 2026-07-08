"""护栏:输入注入检测 + PII 脱敏 + 输出 JSON 校验。

三道防线(呼应面试智练 sensitive_data_redactor):
1. 输入:检测 prompt 注入("忽略以上指令"等)与超长输入;
2. PII:脱敏手机号/邮箱/身份证再入日志;
3. 输出:JSON schema 校验(评估结果必须符合结构)。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# ---------- 1. 输入注入检测 ----------
INJECTION_PATTERNS = [
    re.compile(r"忽略.{0,10}(以上|前面|上面).{0,10}(指令|规则|提示)", re.IGNORECASE),
    re.compile(r"ignore.{0,10}(above|previous|prior).{0,10}(instruction|rule|prompt)", re.IGNORECASE),
    re.compile(r"你(现在|从此).{0,10}(不是|不再).{0,10}(助手|AI|面试官)"),
    re.compile(r"(system|admin|root)\s*[:：]\s*"),  # 角色伪造
    re.compile(r"<\|im_start\|>|<\|system\|>"),  # token 注入
]

MAX_INPUT_LEN = 4000


@dataclass
class GuardResult:
    blocked: bool
    reason: str = ""


def detect_prompt_injection(text: str) -> GuardResult:
    """检测 prompt 注入。命中则 blocked=True。"""
    if not text:
        return GuardResult(blocked=False)
    if len(text) > MAX_INPUT_LEN:
        return GuardResult(blocked=True, reason=f"输入超长({len(text)} > {MAX_INPUT_LEN})")
    for pat in INJECTION_PATTERNS:
        if pat.search(text):
            return GuardResult(blocked=True, reason=f"疑似 prompt 注入:匹配 {pat.pattern[:40]}")
    return GuardResult(blocked=False)


# ---------- 2. PII 脱敏 ----------
PII_PATTERNS = [
    (re.compile(r"1[3-9]\d{9}"), "[手机]"),  # 手机号
    (re.compile(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"), "[邮箱]"),  # 邮箱
    (re.compile(r"\b\d{15}(?:\d{2}[\dXx])?\b"), "[身份证]"),  # 身份证
    (re.compile(r"\b\d{16,19}\b"), "[卡号]"),  # 银行卡
]


def redact_pii(text: str) -> str:
    """脱敏 PII(手机/邮箱/身份证/卡号),用于日志与存储。"""
    if not text:
        return text
    out = text
    for pat, repl in PII_PATTERNS:
        out = pat.sub(repl, out)
    return out


def has_pii(text: str) -> bool:
    if not text:
        return False
    return any(pat.search(text) for pat, _ in PII_PATTERNS)


# ---------- 3. 输出 JSON 校验 ----------
EVAL_SCHEMA_KEYS = {"score", "hit_points", "missed", "mistakes", "feedback"}


def validate_eval_json(obj: dict) -> GuardResult:
    """校验评估输出 JSON 结构。"""
    if not isinstance(obj, dict):
        return GuardResult(blocked=True, reason="评估输出非 dict")
    missing = EVAL_SCHEMA_KEYS - obj.keys()
    if missing:
        return GuardResult(blocked=True, reason=f"评估输出缺字段:{missing}")
    score = obj.get("score")
    if not isinstance(score, (int, float)) or not (0 <= score <= 100):
        return GuardResult(blocked=True, reason=f"score 越界:{score}")
    return GuardResult(blocked=False)
