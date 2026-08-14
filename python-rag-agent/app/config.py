"""配置中心:从环境变量 / .env 加载,对齐 docs/00-实现方案-总览.md §5 三语言一致配置。

基于 pydantic-settings 实现。配置项优先级:环境变量 > .env 文件 > 代码默认值。
字段名与环境变量名映射规则:小写字段名即环境变量名(不区分大小写),
例如 llm_model <-> LLM_MODEL、openai_base_url <-> OPENAI_BASE_URL。

配置分组:
  - 知识库数据源:kb_content_url / kb_content_path / kb_sample_path(三层降级,见 rag/loader.py)
  - LLM / Embedding:OpenAI 兼容,靠 openai_base_url 切换不同提供商
  - embedding_provider:api(走 OpenAI 兼容)/ local(sentence-transformers)/ fastembed(ONNX)
  - vector_store:memory(默认零依赖)/ chroma / pgvector(见 rag/store.py)
  - 检索默认参数:chunk_size/overlap、top_k、rrf_k(深挖可调,见 docs)

全局通过 get_settings()(lru_cache 单例)读取,保证整个进程只解析一次配置。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全局配置容器。所有字段均可被同名环境变量覆盖。

    设计动机:把"环境相关的可变点"(数据源、模型、向量库、阈值)集中到一处,
    业务代码只依赖 get_settings(),便于切换 dev/prod 而无需改代码。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",  # 忽略 .env 中多余变量,避免未知变量导致解析报错
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
    allow_anonymous: bool = True
    api_key_tenants: str = "{}"

    # 检索默认参数(深挖可调)
    rag_top_k_vector: int = 8
    rag_top_k_final: int = 4
    rag_chunk_size: int = 500
    rag_chunk_overlap: int = 80
    rrf_k: int = 60

    @property
    def kb_sample_abs_path(self) -> Path:
        """样例知识库的绝对路径。

        kb_sample_path 若为相对路径,以「项目根目录」(app 的上一级)为基准解析,
        保证无论从哪个工作目录启动服务都能定位到样例数据文件。
        """
        p = Path(self.kb_sample_path)
        if not p.is_absolute():
            p = Path(__file__).resolve().parent.parent / p
        return p


@lru_cache
def get_settings() -> Settings:
    """返回全局唯一的 Settings 实例。

    用 lru_cache 实现单例:整个进程只解析一次环境变量/.env,
    避免每次调用都重新读文件,也保证配置在运行期一致。
    """
    return Settings()
