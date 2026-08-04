"""护栏:输入注入检测 + PII 脱敏 + 输出 JSON 校验。

三道防线(呼应面试智练 sensitive_data_redactor):
1. 输入:检测 prompt 注入("忽略以上指令"等)与超长输入;
2. PII:脱敏手机号/邮箱/身份证再入日志;
3. 输出:JSON schema 校验(评估结果必须符合结构)。

架构位置:横切安全层,被三处调用——
- 输入检测(detect_prompt_injection):API 层在把用户输入交给
  Agent/评估之前先过一道,命中即拒绝,省掉一次 LLM 调用;
- PII 脱敏(redact_pii/has_pii):日志与持久化前调用,防止用户
  隐私(手机号/邮箱/身份证/卡号)明文落盘;
- 输出校验(validate_eval_json):LLM-as-Judge 返回的 JSON 必须
  符合评估 schema 才允许使用,不合格走重试/降级。

设计取舍:这里用正则做轻量启发式,是"便宜的第一道防线"而非
完备安全方案(对抗性变体仍可能绕过);真正的兜底还有评估侧的
重试与降级机制。面试可讲"纵深防御":护栏 + 提示词约束 + 输出校验。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# ---------- 1. 输入注入检测 ----------
# 注入特征正则列表:覆盖常见的越狱/注入手法,命中任意一条即拦截。
# 中英文分开写是因为两种语言的注入话术模式不同;
# .{0,10} 允许关键词之间夹少量字符,对抗简单变体。
INJECTION_PATTERNS = [
    re.compile(r"忽略.{0,10}(以上|前面|上面).{0,10}(指令|规则|提示)", re.IGNORECASE),  # 中文越狱话术
    re.compile(r"ignore.{0,10}(above|previous|prior).{0,10}(instruction|rule|prompt)", re.IGNORECASE),  # 英文越狱话术
    re.compile(r"你(现在|从此).{0,10}(不是|不再).{0,10}(助手|AI|面试官)"),  # 试图让模型脱离当前角色
    re.compile(r"(system|admin|root)\s*[:：]\s*"),  # 角色伪造:冒充系统/管理员前缀
    re.compile(r"<\|im_start\|>|<\|system\|>"),  # token 注入:伪造对话模板特殊标记
]

# 输入长度上限:超长输入既可能是攻击(资源消耗),也没有实际业务意义,
# 在入口处直接拦掉,避免浪费 LLM token。
MAX_INPUT_LEN = 4000


@dataclass
class GuardResult:
    """护栏检测结果:blocked 表示是否拦截,reason 记录拦截原因(可回显给用户/入日志)。"""

    blocked: bool
    reason: str = ""


def detect_prompt_injection(text: str) -> GuardResult:
    """检测 prompt 注入。命中则 blocked=True。

    检查顺序:空输入直接放行 → 超长拦截(先做长度判断,比跑一遍
    正则更便宜,且超长本身就是拒绝理由)→ 逐条正则匹配注入特征。
    reason 中只回显模式前 40 字符,避免把完整正则暴露给调用方。
    """
    if not text:
        return GuardResult(blocked=False)
    if len(text) > MAX_INPUT_LEN:
        return GuardResult(blocked=True, reason=f"输入超长({len(text)} > {MAX_INPUT_LEN})")
    for pat in INJECTION_PATTERNS:
        if pat.search(text):
            return GuardResult(blocked=True, reason=f"疑似 prompt 注入:匹配 {pat.pattern[:40]}")
    return GuardResult(blocked=False)


# ---------- 2. PII 脱敏 ----------
# (正则, 替换文本) 列表:按序对文本做 sub 替换,命中即替换为占位符。
# 顺序无强依赖,但身份证(18位)需先于银行卡(16-19位)的概念区分——
# 这里身份证正则更精确(\d{15}+2位+校验位),银行卡是宽泛的 16-19 位数字。
# 注意:正则只是尽力匹配,不能保证 100% 召回,属于"减少暴露面"而非完全防护。
PII_PATTERNS = [
    (re.compile(r"1[3-9]\d{9}"), "[手机]"),  # 手机号:大陆 1[3-9] 开头 11 位
    (re.compile(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"), "[邮箱]"),  # 邮箱
    (re.compile(r"\b\d{15}(?:\d{2}[\dXx])?\b"), "[身份证]"),  # 身份证:15 位或 18 位(末位可为 X)
    (re.compile(r"\b\d{16,19}\b"), "[卡号]"),  # 银行卡:16-19 位数字
]


def redact_pii(text: str) -> str:
    """脱敏 PII(手机/邮箱/身份证/卡号),用于日志与存储。

    对每条 PII 正则依次做替换,返回脱敏后的文本;空输入原样返回。
    """
    if not text:
        return text
    out = text
    for pat, repl in PII_PATTERNS:
        out = pat.sub(repl, out)
    return out


def has_pii(text: str) -> bool:
    """判断文本是否包含任一 PII 模式(只检测不替换,用于快速决策是否需脱敏)。"""
    if not text:
        return False
    return any(pat.search(text) for pat, _ in PII_PATTERNS)


# ---------- 3. 输出 JSON 校验 ----------
# 评估结果必须具备的字段集合(score 总分 + 四个维度的明细列表)。
EVAL_SCHEMA_KEYS = {"score", "hit_points", "missed", "mistakes", "feedback"}


def validate_eval_json(obj: dict) -> GuardResult:
    """校验评估输出 JSON 结构。

    三条规则:必须是 dict;必须包含 EVAL_SCHEMA_KEYS 全部字段;
    score 必须是 0-100 的数值。任一不满足即 blocked 并给出原因,
    调用方(evaluator)据此决定重试还是降级。
    """
    if not isinstance(obj, dict):
        return GuardResult(blocked=True, reason="评估输出非 dict")
    missing = EVAL_SCHEMA_KEYS - obj.keys()
    if missing:
        return GuardResult(blocked=True, reason=f"评估输出缺字段:{missing}")
    score = obj.get("score")
    if not isinstance(score, (int, float)) or not (0 <= score <= 100):
        return GuardResult(blocked=True, reason=f"score 越界:{score}")
    return GuardResult(blocked=False)
