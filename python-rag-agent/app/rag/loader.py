"""知识库加载器:manifest 驱动,三层数据源降级。这是 RAG 链路的第一环。

职责:把知识库(面试题目 + 学习卡片 + 评分标准 rubric)加载为内存中的
KnowledgeBase 索引,供:
  - 后续切分/向量化(见 api/rag.py ingest);
  - 出题与评估直接读 topic 元数据(recallPrompts / rubric)。

优先级(见 docs/00 §3.1):
  1. KB_CONTENT_PATH  -- 本地 clone(离线覆盖,最快)
  2. KB_CONTENT_URL   -- 公开 manifest(默认,自包含)
  3. KB_SAMPLE_PATH   -- 样例数据(离线快速跑通,3 个 topic)

三层降级的动机:生产/评测用本地全量 clone(快且可离线),默认走远程 manifest
(自包含、无需预置数据),网络不可用时仍能用内置样例快速跑通。

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
    """知识库单个 topic(知识点),字段与 mianshi-zhilian-content 真实 schema 一致。

    关键字段:
      learning_cards  学习卡片(explain/checklist/code/diagram 等),是切分入库的主体;
      recall_prompts  人工撰写的面试回忆题,出题直接用它;
      rubric          评分标准(必答点/加分点/常见错误/维度权重),LLM 评估用它;
      raw             保留原始 JSON,便于调试与扩展。
    """

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
    def from_dict(cls, d: dict[str, Any]) -> Topic:
        """从原始 JSON dict 构造 Topic。

        原始数据是驼峰命名(如 learningCards),这里映射为下划线字段;
        缺失字段用 .get 给默认值,增强对不同 schema 版本的容错。
        """
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
    """知识库内存索引。ingest 后按 id 查找;支持 domain 过滤。

    用 dict 存 id -> Topic,查找 O(1)。是进程内单例数据(见 get_kb),
    出题/评估/切分都以它为唯一数据来源。
    """

    def __init__(self) -> None:
        self._by_id: dict[str, Topic] = {}
        self.content_version: str = ""

    def upsert(self, topic: Topic) -> None:
        """插入或覆盖一个 topic(按 id 去重)。"""
        self._by_id[topic.id] = topic

    def get(self, topic_id: str) -> Topic | None:
        """按 id 精确查找 topic,不存在返回 None。"""
        return self._by_id.get(topic_id)

    def list_topics(self, domain: str | None = None) -> list[Topic]:
        """列出全部 topic;给定 domain 时只返回该领域。"""
        if domain:
            return [t for t in self._by_id.values() if t.domain == domain]
        return list(self._by_id.values())

    def count(self) -> int:
        """返回已加载 topic 数(用于健康检查判断向量库是否就绪)。"""
        return len(self._by_id)

    def all_domains(self) -> list[str]:
        """返回去重排序后的全部领域名。"""
        return sorted({t.domain for t in self._by_id.values()})


_kb: KnowledgeBase | None = None


def get_kb() -> KnowledgeBase:
    """返回全局知识库单例(懒加载)。首次调用时创建空索引。"""
    global _kb
    if _kb is None:
        _kb = KnowledgeBase()
    return _kb


def reset_kb() -> None:
    """重置知识库单例。测试用,避免用例间相互污染。"""
    global _kb
    _kb = None


# ---------- 加载逻辑 ----------

def _load_from_sample(path: Path) -> tuple[list[dict[str, Any]], str]:
    """从单个样例 JSON 文件加载(离线快速跑通用)。返回 (topics, version)。"""
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    topics = data.get("topics", [])
    version = data.get("contentVersion", "sample-unknown")
    return topics, version


def _load_from_local_clone(root: Path) -> tuple[list[dict[str, Any]], str]:
    """本地 clone:读 manifest.json,遍历 domains -> categories -> topics。

    目录结构:manifest.json 声明各 domain 的入口文件(domains/xxx.json),
    每个 domain 文件再列出 categories,每个 category 列出 topic 文件相对路径。
    单个文件缺失仅告警跳过,不中断整体加载(容错)。
    """
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
    """远程 manifest:先拉 manifest,再逐级拉各 domain 与 topic JSON。

    与本地 clone 同构,只是改成 HTTP 拉取。单个 domain/topic 拉取失败仅告警跳过,
    不中断整体加载(网络抖动容错)。
    """
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
    """加载知识库到内存索引(异步,支持远程)。

    数据源选择优先级:
      source 显式指定(目录->本地 clone;文件->单文件样例)
      > kb_content_path(本地 clone)> kb_content_url(远程 manifest)> 样例。
    远程拉取失败时自动降级到样例,保证始终能拿到可用数据。
    只保留 status=="production" 的 topic。完成后替换全局单例 _kb。

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
            continue  # 草稿/废弃 topic 不入库
        kb.upsert(t)
        prod_count += 1

    logger.info("知识库加载完成:version=%s, production topics=%d", version, prod_count)

    global _kb
    _kb = kb
    return kb


def load_kb_sync(settings: Settings | None = None) -> KnowledgeBase:
    """同步加载:仅走本地 clone 或样例(无网络、供测试用);远程 manifest 请用 async load_kb。

    因为不做网络请求,启动预加载与测试都用它,避免引入事件循环依赖。
    """
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
