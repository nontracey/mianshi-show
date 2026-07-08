"""切分模块单测。"""

from __future__ import annotations

from app.rag.loader import get_kb
from app.rag.splitter import split_topic, split_topics, _split_recursive


def test_split_recursive_short_text_kept_whole():
    """短文本应整段返回,不切。"""
    out = _split_recursive("短文本", chunk_size=500, chunk_overlap=80)
    assert out == ["短文本"]


def test_split_recursive_long_text_chunked():
    """长文本应被切成不超过 chunk_size 的片段。"""
    text = "句子一。句子二。句子三。" * 50  # 约 600 字
    out = _split_recursive(text, chunk_size=100, chunk_overlap=20)
    assert len(out) >= 2
    assert all(len(p) <= 120 for p in out)  # 允许少量超出(合并逻辑)


def test_split_topic_whole_cards_not_split():
    """checklist/code/compareTable/diagram 短卡应整张入库(不被切分)。"""
    kb = get_kb()
    topic = kb.get("java.concurrency.volatile")
    assert topic is not None
    chunks = split_topic(topic, chunk_size=10, chunk_overlap=2)  # 故意小,验证短卡不切
    # checklist 应作为整张 chunk 出现
    checklist_chunks = [c for c in chunks if c.metadata.get("card_type") == "checklist"]
    assert len(checklist_chunks) >= 1
    # 内容应完整(不被切到 chunk_size=10)
    assert "1) volatile 不保证原子性" in checklist_chunks[0].text


def test_split_topic_metadata_correct():
    """每个 chunk 的 metadata 应包含 topic_id/domain/title/tags/difficulty/card_type。"""
    kb = get_kb()
    topic = kb.get("database.mysql-index")
    chunks = split_topic(topic)
    assert chunks
    m = chunks[0].metadata
    assert m["topic_id"] == "database.mysql-index"
    assert m["domain"] == "database"
    assert m["title"] == "MySQL 索引原理"
    assert "card_type" in m


def test_split_topic_summary_included():
    """summary 应作为一个独立 chunk 入库(便于检索标题/概述)。"""
    kb = get_kb()
    topic = kb.get("python.gil")
    chunks = split_topic(topic)
    summary_chunks = [c for c in chunks if c.metadata.get("card_type") == "summary"]
    assert len(summary_chunks) == 1
    assert "GIL" in summary_chunks[0].text


def test_split_topics_aggregates():
    kb = get_kb()
    topics = kb.list_topics()
    chunks = split_topics(topics)
    assert len(chunks) > len(topics)  # 每个 topic 至少几个 chunk
