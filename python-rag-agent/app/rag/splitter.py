"""文档切分:把 topic 的 learningCards 拆成 chunk。这是 RAG 链路的第二环。

核心设计——按卡片类型分流:
- explain/interviewAnswer 长文用 RecursiveCharacterTextSplitter 切;
- checklist/compareTable/code 短卡整张入库(切了反而破坏结构);
- diagram 的 content 是 mermaid 源码,整张入库(fallback 文本也存)。

为什么这样分:本知识库以"短而结构化"的卡片为主,整卡语义完整,强行切分会
破坏列表/表格/代码的完整性;只有长段落才需要切。因此切分前先按类型判断。

chunk_size 500 / overlap 80 是经验值:多数短问答能整条入库,长答案才切,
overlap 80 保证边界语义不断裂。

为避免 langchain 依赖过重,这里实现一个等价的最简递归切分器;
若 langchain 可用则优先用官方实现(深挖可对比)。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.config import get_settings
from app.rag.loader import Topic

# 整张入库的 card 类型(短/结构化,切了破坏语义):清单、对比表、代码、图示
WHOLE_CARD_TYPES = {"checklist", "compareTable", "code", "diagram"}
# 需要切分的 card 类型(长文):讲解、面试参考答案
SPLIT_CARD_TYPES = {"explain", "interviewAnswer"}


@dataclass
class Chunk:
    """切分后的单个文本片段。

    text     片段正文,用于向量化与 BM25 索引;
    metadata 溯源与过滤信息(topic_id/domain/title/tags/difficulty/card_type),
             检索命中后据此回填"来源",实现答案可溯源。
    """

    text: str
    metadata: dict[str, Any] = field(default_factory=dict)


def _split_recursive(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """最简递归切分:按段落 -> 句子 -> 字符 三级降级。

    与 langchain RecursiveCharacterTextSplitter 思路一致,但无外部依赖。

    三步:
      1. 依次尝试更细的分隔符(段落->换行->句号->空格),把文本拆到每段都不超
         chunk_size 为止。优先在语义边界(段落/句子)断开,尽量不把句子切碎。
      2. 仍有超长片段(如没有任何标点的长串)时按字符硬切兜底。
      3. 把碎片重新拼装回接近 chunk_size 的块,并在相邻块间保留 chunk_overlap
         字符的重叠,避免关键信息恰好落在切分边界而丢失。
    """
    if len(text) <= chunk_size:
        return [text] if text.strip() else []

    # 分隔符优先级:从最"语义完整"的段落级,逐级降到字符级。
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
            # 新块以旧块尾部 chunk_overlap 个字符开头,实现相邻块重叠,避免边界语义断裂。
            buf = buf[-chunk_overlap:] + p
        else:
            buf = (buf + p) if buf else p
    if buf.strip():
        out.append(buf.strip())
    return out


def split_topic(topic: Topic, *, chunk_size: int | None = None, chunk_overlap: int | None = None) -> list[Chunk]:
    """把单个 topic 的 learningCards 切成 chunk,带 metadata(topic_id/domain/title/tags/difficulty/card_type)。

    参数:
      chunk_size/chunk_overlap 缺省时取配置默认值(rag_chunk_size/rag_chunk_overlap)。
    逻辑:
      遍历每张学习卡片,按类型分流:短结构化卡整张入库、长文递归切分;
      最后把 summary 也单独作为一个 chunk 入库。
    返回:Chunk 列表(空卡跳过)。
    """
    s = get_settings()
    cs = chunk_size or s.rag_chunk_size
    co = chunk_overlap or s.rag_chunk_overlap

    chunks: list[Chunk] = []
    # 所有 chunk 共享的溯源 metadata:检索命中后据此回填来源。
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
            continue  # 空卡片跳过

        meta = {**base_meta, "card_type": ctype, "card_title": title}

        if ctype in WHOLE_CARD_TYPES:
            # 短/结构化卡整张入库,不切分(切了破坏清单/表格/代码结构)。
            chunks.append(Chunk(text=content, metadata=meta))
            # diagram 的 fallback 文本也入库(便于检索)
            fb = card.get("fallback")
            if ctype == "diagram" and fb:
                chunks.append(Chunk(text=fb, metadata={**meta, "card_title": f"{title}(文本版)"}))
        elif ctype in SPLIT_CARD_TYPES:
            # 长文(explain/interviewAnswer)递归切分。
            for piece in _split_recursive(content, cs, co):
                chunks.append(Chunk(text=piece, metadata=meta))
        else:
            # 未知类型保守整张入库。
            chunks.append(Chunk(text=content, metadata=meta))

    # summary 也作为一个 chunk(短摘要,检索标题/概述时命中)
    if topic.summary:
        chunks.append(Chunk(text=topic.summary, metadata={**base_meta, "card_type": "summary", "card_title": "摘要"}))

    return chunks


def split_topics(topics: list[Topic], **kwargs) -> list[Chunk]:
    """批量切分多个 topic,聚合为单个 chunk 列表(供 ingest 一次性入库)。"""
    out: list[Chunk] = []
    for t in topics:
        out.extend(split_topic(t, **kwargs))
    return out
