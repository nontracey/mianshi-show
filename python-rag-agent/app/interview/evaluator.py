"""LLM-as-judge 评估:按 topic 的 rubric 结构化打分,temperature=0 保证可复现。

rubric 与「面试智练」App 同源(mianshi-zhilian-content),客户端/服务端同一份评分标准。
强制 JSON 输出;解析失败重试一次,仍失败则降级为文本反馈(degraded=true)。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from app.infra.llm import LLMClient, LLMError, get_llm
from app.rag.loader import KnowledgeBase, Topic, get_kb
from app.schemas import Evaluation

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """你是资深技术面试官,按给定评分标准客观评估候选人回答,输出严格 JSON。

评分维度与权重(来自该知识点的真实评分标准,与「面试智练」App 同源):
- coverage(覆盖度):{weight_coverage} 分
- accuracy(准确性):{weight_accuracy} 分
- interviewExpression(表达):{weight_expression} 分
- depth(深度):{weight_depth} 分
总分 = 各维度按权重加权(0-100)。

评分标准:
- 必答点(must_have):{must_have}
- 加分点(good_to_have):{good_to_have}
- 常见错误(common_mistakes):{common_mistakes}

评估要求:
1. 命中的必答点放进 hit_points;遗漏的放进 missed。
2. 候选人犯的常见错误放进 mistakes。
3. feedback 给出具体改进建议(中文,2-4 句)。

输出 JSON 严格符合如下结构(不要输出任何其他内容):
{{
  "score": 0-100,
  "dimension_scores": {{"coverage": 0-100, "accuracy": 0-100, "interviewExpression": 0-100, "depth": 0-100}},
  "hit_points": ["命中的必答点..."],
  "missed": ["遗漏的必答点..."],
  "mistakes": ["犯的常见错误..."],
  "feedback": "改进建议"
}}
"""


def _build_system_prompt(rubric: dict[str, Any]) -> str:
    weights = rubric.get("scoreWeights", {})
    return SYSTEM_PROMPT.format(
        weight_coverage=weights.get("coverage", 25),
        weight_accuracy=weights.get("accuracy", 30),
        weight_expression=weights.get("interviewExpression", 20),
        weight_depth=weights.get("depth", 25),
        must_have=json.dumps(rubric.get("mustHave", []), ensure_ascii=False),
        good_to_have=json.dumps(rubric.get("goodToHave", []), ensure_ascii=False),
        common_mistakes=json.dumps(rubric.get("commonMistakes", []), ensure_ascii=False),
    )


def _parse_eval(content: Any) -> Evaluation:
    """把 LLM 返回的 JSON dict 转 Evaluation。容错字段名。"""
    def _lift(*keys: str, default: Any = None) -> Any:
        for k in keys:
            if k in content and content[k] is not None:
                return content[k]
        return default

    hit = _lift("hit_points", "hit", "hitPoints", default=[])
    missed = _lift("missed", "missed_points", "missedPoints", default=[])
    mistakes = _lift("mistakes", "common_mistakes_hit", default=[])

    dim = _lift("dimension_scores", "dimensionScores", default={})
    if not isinstance(dim, dict):
        dim = {}

    score = _lift("score", default=0)
    try:
        score = int(round(float(score)))
    except (TypeError, ValueError):
        score = 0
    score = max(0, min(100, score))

    return Evaluation(
        score=score,
        dimension_scores={k: int(v) for k, v in dim.items()} if isinstance(dim, dict) else {},
        hit=[str(x) for x in hit] if isinstance(hit, list) else [],
        missed=[str(x) for x in missed] if isinstance(missed, list) else [],
        mistakes=[str(x) for x in mistakes] if isinstance(mistakes, list) else [],
        feedback=str(_lift("feedback", default="")),
    )


async def evaluate_answer(
    question_id: str,
    user_answer: str,
    *,
    llm: LLMClient | None = None,
    kb: KnowledgeBase | None = None,
) -> Evaluation:
    """评估用户回答。temperature=0 保证可复现。

    question_id 形如 java.concurrency.volatile.recall.1,前缀即 topic id。
    """
    client = llm or get_llm()
    base = kb or get_kb()

    topic_id = _extract_topic_id(question_id)
    topic: Topic | None = base.get(topic_id)
    if topic is None:
        raise ValueError(f"question_id 对应的 topic 不存在:{topic_id}(from {question_id})")

    rubric = topic.rubric or {}
    if not rubric.get("mustHave"):
        raise ValueError(f"topic 缺少 rubric.mustHave,无法评估:{topic_id}")

    system_prompt = _build_system_prompt(rubric)
    # 找到对应 recallPrompt 作为评估上下文(评估时知道"在问什么")
    question_text = ""
    for p in topic.recall_prompts or []:
        if p.get("id") == question_id:
            question_text = p.get("prompt", "")
            break

    user_msg = f"题目:{question_text}\n\n候选人回答:\n{user_answer}"
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_msg},
    ]

    try:
        content, _ = await client.chat_json(messages, temperature=0.0)
    except LLMError as e:
        logger.warning("评估 JSON 解析失败,重试一次:%s", e)
        try:
            content, _ = await client.chat_json(messages, temperature=0.0)
        except LLMError as e2:
            logger.error("评估重试仍失败,降级为文本反馈:%s", e2)
            return Evaluation(
                score=0,
                feedback=f"评估服务暂时不可用,请稍后重试。原始错误:{e2}",
                degraded=True,
            )

    eval_result = _parse_eval(content)
    return eval_result


def _extract_topic_id(question_id: str) -> str:
    """question_id = topic_id + '.recall.N'。剥掉最后两段。"""
    parts = question_id.split(".")
    if len(parts) >= 3 and parts[-2] == "recall":
        return ".".join(parts[:-2])
    # fallback:剥最后一段
    return ".".join(parts[:-1]) if "." in question_id else question_id
