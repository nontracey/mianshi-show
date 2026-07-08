"""配置:从环境变量加载,对齐 docs/00-实现方案-总览.md §5 三语言一致配置。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # 知识库数据源
    kb_content_url: str = "https://raw.githubusercontent.com/nontracey/mianshi-zhilian-content/main/manifest.json"
    kb_content_path: str = ""
    kb_sample_path: str = "../data/knowledge_base.sample.json"

    # LLM / Embedding(OpenAI 兼容)
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    llm_model: str = "gpt-4o-mini"
    embedding_model: str = "text-embedding-3-small"

    # Embedding 提供方:api(OpenAI 兼容,走 openai_base_url)| local(本地 sentence-transformers,免费离线)
    embedding_provider: str = "api"
    local_embedding_model: str = "BAAI/bge-small-zh-v1.5"

    # 向量库:memory(默认,零依赖,dev/demo)/ chroma(可选,持久化)/ pgvector(prod)
    vector_store: str = "memory"
    chroma_path: str = "./chroma_data"
    pgvector_url: str = ""

    # 缓存
    redis_url: str = ""

    # 服务
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    rate_limit_per_minute: int = 20

    # 检索默认参数(深挖可调)
    rag_top_k_vector: int = 8
    rag_top_k_final: int = 4
    rag_chunk_size: int = 500
    rag_chunk_overlap: int = 80
    rrf_k: int = 60

    @property
    def kb_sample_abs_path(self) -> Path:
        p = Path(self.kb_sample_path)
        if not p.is_absolute():
            p = Path(__file__).resolve().parent.parent / p
        return p


@lru_cache
def get_settings() -> Settings:
    return Settings()
