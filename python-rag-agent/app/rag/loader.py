"""知识库加载器:manifest 驱动,三层数据源降级。

优先级(见 docs/00 §3.1):
  1. KB_CONTENT_PATH  -- 本地 clone(离线覆盖,最快)
  2. KB_CONTENT_URL   -- 公开 manifest(默认,自包含)
  3. KB_SAMPLE_PATH   -- 样例数据(离线快速跑通,3 个 topic)

只入 status=="production" 的 topic。manifest 的 contentVersion 用于 benchmark 复现。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

from app.config import Settings, get_settings

logger = logging.getLogger(__name__)


@dataclass
class Topic:
    """知识库单个 topic,字段与 mianshi-zhilian-content 真实 schema 一致。"""

    id: str
    domain: str
    category: str
    title: str
    summary: str
    tags: list[str]
    difficulty: int
    status: str
    interview_frequency: str = ""
    interviewer_focus: str = ""
    learning_cards: list[dict[str, Any]] = None  # type: ignore[assignment]
    recall_prompts: list[dict[str, Any]] = None  # type: ignore[assignment]
    rubric: dict[str, Any] = None  # type: ignore[assignment]
    raw: dict[str, Any] = None  # type: ignore[assignment]

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "Topic":
        return cls(
            id=d["id"],
            domain=d.get("domain", ""),
            category=d.get("category", ""),
            title=d.get("title", ""),
            summary=d.get("summary", ""),
            tags=d.get("tags", []),
            difficulty=d.get("difficulty", 3),
            status=d.get("status", ""),
            interview_frequency=d.get("interviewFrequency", ""),
            interviewer_focus=d.get("interviewerFocus", ""),
            learning_cards=d.get("learningCards", []),
            recall_prompts=d.get("recallPrompts", []),
            rubric=d.get("rubric", {}),
            raw=d,
        )


class KnowledgeBase:
    """知识库内存索引。ingest 后按 id 查找;支持 domain 过滤。"""

    def __init__(self) -> None:
        self._by_id: dict[str, Topic] = {}
        self.content_version: str = ""

    def upsert(self, topic: Topic) -> None:
        self._by_id[topic.id] = topic

    def get(self, topic_id: str) -> Topic | None:
        return self._by_id.get(topic_id)

    def list_topics(self, domain: str | None = None) -> list[Topic]:
        if domain:
            return [t for t in self._by_id.values() if t.domain == domain]
        return list(self._by_id.values())

    def count(self) -> int:
        return len(self._by_id)

    def all_domains(self) -> list[str]:
        return sorted({t.domain for t in self._by_id.values()})


_kb: KnowledgeBase | None = None


def get_kb() -> KnowledgeBase:
    global _kb
    if _kb is None:
        _kb = KnowledgeBase()
    return _kb


def reset_kb() -> None:
    global _kb
    _kb = None


# ---------- 加载逻辑 ----------

def _load_from_sample(path: Path) -> tuple[list[dict[str, Any]], str]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    topics = data.get("topics", [])
    version = data.get("contentVersion", "sample-unknown")
    return topics, version


def _load_from_local_clone(root: Path) -> tuple[list[dict[str, Any]], str]:
    """本地 clone:读 manifest.json,遍历 domains -> categories -> topics。"""
    manifest_path = root / "manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError(f"本地 clone 未找到 manifest.json:{manifest_path}")
    with manifest_path.open("r", encoding="utf-8") as f:
        manifest = json.load(f)
    version = manifest.get("contentVersion", "local-unknown")
    topics: list[dict[str, Any]] = []
    for domain in manifest.get("domains", []):
        entry = domain.get("entry")  # 如 domains/java.json
        domain_file = root / entry
        if not domain_file.exists():
            logger.warning("domain 文件缺失:%s", domain_file)
            continue
        with domain_file.open("r", encoding="utf-8") as f:
            d_data = json.load(f)
        for cat in d_data.get("categories", []):
            for topic_path in cat.get("topics", []):
                topic_file = root / topic_path  # root-relative
                if not topic_file.exists():
                    logger.warning("topic 文件缺失:%s", topic_file)
                    continue
                with topic_file.open("r", encoding="utf-8") as f:
                    topics.append(json.load(f))
    return topics, version


async def _load_from_remote(url: str) -> tuple[list[dict[str, Any]], str]:
    """远程 manifest:先拉 manifest,再拉各 domain 与 topic JSON。"""
    async with httpx.AsyncClient(timeout=30.0) as client:
        manifest = (await client.get(url)).json()
        version = manifest.get("contentVersion", "remote-unknown")
        base = url.rsplit("/", 1)[0]  # .../main
        topics: list[dict[str, Any]] = []
        for domain in manifest.get("domains", []):
            entry = domain.get("entry")  # domains/java.json
            d_url = f"{base}/{entry}"
            try:
                d_data = (await client.get(d_url)).json()
            except Exception as e:
                logger.warning("拉取 domain 失败 %s:%s", d_url, e)
                continue
            for cat in d_data.get("categories", []):
                for topic_path in cat.get("topics", []):
                    t_url = f"{base}/{topic_path}"  # root-relative
                    try:
                        topics.append((await client.get(t_url)).json())
                    except Exception as e:
                        logger.warning("拉取 topic 失败 %s:%s", t_url, e)
    return topics, version


async def load_kb(settings: Settings | None = None, *, source: str | None = None) -> KnowledgeBase:
    """加载知识库到内存索引。source 显式指定路径时覆盖默认优先级。

    返回填充后的 KnowledgeBase;同时更新全局单例。
    """
    s = settings or get_settings()
    kb = KnowledgeBase()

    topics: list[dict[str, Any]] = []
    version = ""

    if source:
        src_path = Path(source)
        if src_path.is_dir():
            topics, version = _load_from_local_clone(src_path)
        else:
            topics, version = _load_from_sample(src_path)
    elif s.kb_content_path:
        topics, version = _load_from_local_clone(Path(s.kb_content_path))
    elif s.kb_content_url:
        try:
            topics, version = await _load_from_remote(s.kb_content_url)
        except Exception as e:
            logger.warning("远程 manifest 拉取失败,降级到样例:%s", e)
            topics, version = _load_from_sample(s.kb_sample_abs_path)
    else:
        topics, version = _load_from_sample(s.kb_sample_abs_path)

    kb.content_version = version
    prod_count = 0
    for t_dict in topics:
        t = Topic.from_dict(t_dict)
        if t.status != "production":
            continue
        kb.upsert(t)
        prod_count += 1

    logger.info("知识库加载完成:version=%s, production topics=%d", version, prod_count)

    global _kb
    _kb = kb
    return kb


def load_kb_sync(settings: Settings | None = None) -> KnowledgeBase:
    """同步加载:仅走本地 clone 或样例(无网络、供测试用);远程 manifest 请用 async load_kb。"""
    s = settings or get_settings()
    kb = KnowledgeBase()
    if s.kb_content_path:
        topics, version = _load_from_local_clone(Path(s.kb_content_path))
    else:
        topics, version = _load_from_sample(s.kb_sample_abs_path)
    kb.content_version = version
    for t_dict in topics:
        t = Topic.from_dict(t_dict)
        if t.status == "production":
            kb.upsert(t)
    global _kb
    _kb = kb
    return kb
