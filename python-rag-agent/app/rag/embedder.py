"""Embedding 封装:批量 embed + 简单内存缓存(同文本不重复 embed)。RAG 链路第三环。

职责:把文本(chunk 正文或查询)转成向量,供向量库写入与检索。

两种后端(由 settings.embedding_provider 决定):
  - api:  走 LLMClient.embed(OpenAI 兼容,如智谱/OpenAI),需 OPENAI_API_KEY 及余额
  - local:本地 sentence-transformers(默认 BAAI/bge-small-zh-v1.5),免费离线,适合 demo/评测
  - fastembed:ONNX 后端(不依赖 torch),同样免费离线

设计要点:
  - 内置按文本去重的内存缓存:同一文本只 embed 一次,ingest 时大量重复 summary
    等能省真实调用;
  - CPU 密集的本地模型推理丢到线程池,避免阻塞 asyncio 事件循环;
  - 批量上限保守取 256(OpenAI embeddings 单次上限 2048)。
"""

from __future__ import annotations

from app.config import Settings, get_settings
from app.infra.llm import LLMClient, get_llm

_BATCH = 256


class Embedder:
    """文本向量化器。按 provider 分流到 api/local/fastembed 后端。

    关键属性:
      _llm          api 后端复用的 LLMClient(缺省懒加载全局单例);
      _cache        text -> vector 的内存去重缓存;
      _provider     后端类型(api/local/fastembed);
      _st_model     懒加载的本地模型实例(首次用时才 import,避免无谓依赖开销)。
    """

    def __init__(
        self,
        llm: LLMClient | None = None,
        *,
        settings: Settings | None = None,
    ) -> None:
        self._llm = llm
        self._cache: dict[str, list[float]] = {}
        s = settings or get_settings()
        self._provider = s.embedding_provider
        self._local_model_name = s.local_embedding_model
        self._st_model = None  # 懒加载的本地模型

    def _client(self) -> LLMClient:
        """懒加载全局 LLMClient(api 后端用)。"""
        if self._llm is None:
            self._llm = get_llm()
        return self._llm

    def _get_local_model(self):
        """懒加载 sentence-transformers 本地模型(local 后端用)。"""
        if self._st_model is None:
            from sentence_transformers import SentenceTransformer

            self._st_model = SentenceTransformer(self._local_model_name)
        return self._st_model

    def _get_fastembed_model(self):
        """懒加载 fastembed(ONNX)模型(fastembed 后端用)。"""
        if self._st_model is None:
            from fastembed import TextEmbedding

            self._st_model = TextEmbedding(model_name=self._local_model_name)
        return self._st_model

    async def _embed_raw(self, texts: list[str]) -> list[list[float]]:
        """真正产生向量的地方,按 provider 分流。CPU 计算丢线程池避免阻塞事件循环。"""
        if self._provider == "fastembed":
            # ONNX 后端,不依赖 torch;bge 类模型输出已可直接 cosine。
            import anyio

            model = self._get_fastembed_model()

            def _encode() -> list[list[float]]:
                return [v.tolist() for v in model.embed(texts)]

            return await anyio.to_thread.run_sync(_encode)
        if self._provider == "local":
            import anyio

            model = self._get_local_model()
            # SentenceTransformer.encode 是同步 CPU 计算,丢到线程池避免阻塞事件循环。
            # normalize_embeddings=True -> 输出单位向量,cosine 检索直接可用。
            def _encode() -> list[list[float]]:
                return model.encode(texts, normalize_embeddings=True).tolist()

            return await anyio.to_thread.run_sync(_encode)
        return await self._client().embed(texts)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """批量向量化。分批(每批 _BATCH 条),批内先查缓存去重,只对未命中的
        文本做真实 embed,再按输入顺序拼回结果。空输入返回空列表。"""
        if not texts:
            return []
        out: list[list[float]] = []
        for i in range(0, len(texts), _BATCH):
            batch = texts[i : i + _BATCH]
            # 命中缓存的跳过真实调用
            miss_idx = [j for j, t in enumerate(batch) if t not in self._cache]
            if miss_idx:
                miss_texts = [batch[j] for j in miss_idx]
                embs = await self._embed_raw(miss_texts)
                for j, e in zip(miss_idx, embs, strict=True):
                    self._cache[batch[j]] = e
            for t in batch:
                out.append(self._cache[t])
        return out

    async def embed_one(self, text: str) -> list[float]:
        """单条文本向量化(复用 embed 的分批与缓存逻辑)。"""
        res = await self.embed([text])
        return res[0]


_embedder: Embedder | None = None


def get_embedder() -> Embedder:
    """返回全局 Embedder 单例(懒加载)。"""
    global _embedder
    if _embedder is None:
        _embedder = Embedder()
    return _embedder


def reset_embedder() -> None:
    """重置 Embedder 单例。测试用。"""
    global _embedder
    _embedder = None


def set_embedder(embedder: Embedder) -> None:
    """测试用:注入 embedder 覆盖单例。"""
    global _embedder
    _embedder = embedder
