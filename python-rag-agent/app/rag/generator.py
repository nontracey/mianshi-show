"""生成器:拼 context + System Prompt(防幻觉)-> LLM -> 抽取来源。RAG 链路第六环。

防幻觉的 Prompt 层策略(见 docs/01 §5):System Prompt 强制"只依据上下文回答、
标注来源、不知道就说不知道",从提示词层面约束模型不编造。配合检索层保证召回质量,
构成多层防幻觉。

流程:把检索到的 docs 拼成带编号的 context -> 填进 System Prompt -> 调 LLM ->
从 docs 的 metadata 抽取去重后的来源列表(可溯源)。

System Prompt 见下方 SYSTEM_PROMPT(见 docs/01 §5)。
"""

from __future__ import annotations

import logging
from typing import Any

from app.infra.llm import LLMError, get_llm
from app.rag.retriever import RetrievalResult
from app.schemas import AskData, Source

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """你是严谨的技术面试知识助手。只依据【上下文】回答,标注来源条目 id。
上下文没有的内容,直接说"知识库中没有相关内容",不要编造。
回答结构:先直接答,再分点展开(若涉及),最后用 [来源:id] 标注引用。

【上下文】
{context}
"""


def _build_context(docs) -> str:
    """把检索结果拼成带编号的 context,便于 LLM 引用 id。

    每条格式:[序号] id=topic_id | 标题(卡片类型)\\n正文。编号让模型在回答里
    能用 [来源:id] 精确指回某条,支撑可溯源。
    """
    if not docs:
        return "(空)"
    lines = []
    for i, d in enumerate(docs, 1):
        topic_id = d.metadata.get("topic_id", "unknown")
        title = d.metadata.get("title", "")
        card_type = d.metadata.get("card_type", "")
        lines.append(f"[{i}] id={topic_id} | {title}({card_type})\n{d.text}")
    return "\n\n".join(lines)


def _extract_sources(docs) -> list[Source]:
    """从检索结果抽取来源列表:按 topic_id 去重,保留首次出现顺序(相关度更高)。

    score 保留四位小数,供前端展示"相关度"。
    """
    seen: set[str] = set()
    sources: list[Source] = []
    for d in docs:
        tid = d.metadata.get("topic_id", "")
        if tid and tid not in seen:
            seen.add(tid)
            sources.append(
                Source(
                    id=tid,
                    topic=d.metadata.get("title", ""),
                    score=round(float(d.score), 4),
                    card_type=d.metadata.get("card_type", ""),
                )
            )
    return sources


async def generate(
    question: str,
    retrieval: RetrievalResult,
    *,
    temperature: float = 0.3,
    stream: bool = False,
) -> AskData | Any:
    """根据检索结果生成答案。stream=True 时返回 async iterator(token 流)。

    流程:拼 context -> 组装 system/user 消息 -> 调 LLM。stream 模式直接把
    LLMClient.chat_stream 的 token 流交给上层(api/rag.py 逐 token SSE 推送);
    非流式返回 AskData(答案+来源+用量)。LLMError 透传给上层处理(503)。
    """
    context = _build_context(retrieval.docs)
    system_msg = SYSTEM_PROMPT.format(context=context)
    messages = [
        {"role": "system", "content": system_msg},
        {"role": "user", "content": question},
    ]

    client = get_llm()
    sources = _extract_sources(retrieval.docs)

    if stream:
        # 流式:返回 token 迭代器,由调用方逐 token 转发(SSE)。
        return client.chat_stream(messages, temperature=temperature)

    try:
        answer, usage = await client.chat(messages, temperature=temperature)
    except LLMError as e:
        logger.warning("generate 失败:%s", e)
        raise

    return AskData(answer=answer, sources=sources, usage=usage)
