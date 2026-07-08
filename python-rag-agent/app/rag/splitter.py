"""文档切分:把 topic 的 learningCards 拆成 chunk。

策略(见 docs/01 §3.1):
- explain/interviewAnswer 长文用 RecursiveCharacterTextSplitter 切;
- checklist/compareTable/code 短卡整张入库(切了反而破坏结构);
- diagram 的 content 是 mermaid 源码,整张入库(fallback 文本也存)。

为避免 langchain 依赖过重,这里实现一个等价的最简递归切分器;
若 langchain 可用则优先用官方实现(深挖可对比)。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.config import get_settings
from app.rag.loader import Topic

# 整张入库的 card 类型(短/结构化,切了破坏语义)
WHOLE_CARD_TYPES = {"checklist", "compareTable", "code", "diagram"}
# 需要切分的 card 类型(长文)
SPLIT_CARD_TYPES = {"explain", "interviewAnswer"}


@dataclass
class Chunk:
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)


def _split_recursive(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """最简递归切分:按段落 -> 句子 -> 字符 三级降级。

    与 langchain RecursiveCharacterTextSplitter 思路一致,但无外部依赖。
    """
    if len(text) <= chunk_size:
        return [text] if text.strip() else []

    seps = ["\n\n", "\n", "。", ".", "!", "?", ";", ";", " "]
    pieces = [text]
    for sep in seps:
        new_pieces = []
        for p in pieces:
            if len(p) <= chunk_size:
                new_pieces.append(p)
            else:
                new_pieces.extend(s for s in p.split(sep) if s)
        pieces = new_pieces
        if all(len(p) <= chunk_size for p in pieces):
            break

    # 仍有超长 piece:按字符硬切(最后的兜底)
    final: list[str] = []
    for p in pieces:
        if len(p) <= chunk_size:
            final.append(p)
        else:
            for i in range(0, len(p), chunk_size):
                final.append(p[i : i + chunk_size])
    pieces = final

    # 合并成 chunk_size 大小,带 overlap
    out: list[str] = []
    buf = ""
    for p in pieces:
        if not p:
            continue
        if buf and len(buf) + len(p) + 1 > chunk_size:
            out.append(buf.strip())
            buf = buf[-chunk_overlap:] + p
        else:
            buf = (buf + p) if buf else p
    if buf.strip():
        out.append(buf.strip())
    return out


def split_topic(topic: Topic, *, chunk_size: int | None = None, chunk_overlap: int | None = None) -> list[Chunk]:
    """把单个 topic 的 learningCards 切成 chunk,带 metadata(topic_id/domain/title/tags/difficulty/card_type)。"""
    s = get_settings()
    cs = chunk_size or s.rag_chunk_size
    co = chunk_overlap or s.rag_chunk_overlap

    chunks: list[Chunk] = []
    base_meta = {
        "topic_id": topic.id,
        "domain": topic.domain,
        "category": topic.category,
        "title": topic.title,
        "tags": topic.tags,
        "difficulty": topic.difficulty,
    }

    for card in topic.learning_cards or []:
        ctype = card.get("type", "explain")
        title = card.get("title", "")
        content = card.get("content", "")
        if not content:
            continue

        meta = {**base_meta, "card_type": ctype, "card_title": title}

        if ctype in WHOLE_CARD_TYPES:
            chunks.append(Chunk(text=content, metadata=meta))
            # diagram 的 fallback 文本也入库(便于检索)
            fb = card.get("fallback")
            if ctype == "diagram" and fb:
                chunks.append(Chunk(text=fb, metadata={**meta, "card_title": f"{title}(文本版)"}))
        elif ctype in SPLIT_CARD_TYPES:
            for piece in _split_recursive(content, cs, co):
                chunks.append(Chunk(text=piece, metadata=meta))
        else:
            chunks.append(Chunk(text=content, metadata=meta))

    # summary 也作为一个 chunk(短摘要,检索标题/概述时命中)
    if topic.summary:
        chunks.append(Chunk(text=topic.summary, metadata={**base_meta, "card_type": "summary", "card_title": "摘要"}))

    return chunks


def split_topics(topics: list[Topic], **kwargs) -> list[Chunk]:
    out: list[Chunk] = []
    for t in topics:
        out.extend(split_topic(t, **kwargs))
    return out
