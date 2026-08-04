"""LLM 封装:OpenAI 兼容客户端,统一 chat/embedding 调用 + token 计数 + 指标记录。

这是所有业务模块调用大模型的唯一出口,集中处理:请求构造、异常包装(LLMError)、
token 用量统计、指标上报(observability)。

支持通义/DeepSeek/OpenAI/本地模型,靠 OPENAI_BASE_URL 切换。
评估场景调用方显式传 temperature=0 保证可复现(M1 验收要点)。

方法一览:
  chat            非流式对话(返回 content + usage);
  chat_json       强制 JSON 输出并解析(失败抛 LLMError 供上层降级);
  chat_stream     流式对话(逐 token yield,供 SSE);
  chat_with_tools Function Calling(返回 tool_calls);
  embed           批量 embedding。

单例:get_llm() 懒加载;测试可用 set_llm()/reset_llm() 注入 FakeLLM。
"""

from __future__ import annotations

import json
import logging
from typing import Any, AsyncIterator

from openai import AsyncOpenAI

from app.config import Settings, get_settings
from app.infra.observability import get_metrics

logger = logging.getLogger(__name__)


class LLMError(RuntimeError):
    """LLM 调用相关异常的统一类型,便于上层捕获并降级/返回 503。"""


class LLMClient:
    """OpenAI 兼容 LLM 客户端。chat 与 embed 共用同一 client。

    把所有模型调用收敛到这里,统一处理参数透传、异常包装、token 计量与指标上报。
    """

    def __init__(self, settings: Settings | None = None) -> None:
        s = settings or get_settings()
        if not s.openai_api_key:
            # 快速失败:缺 key 直接抛错,避免运行到一半才发现无法调用。
            raise LLMError("OPENAI_API_KEY 未配置;请复制 .env.example 为 .env 并填入。")
        self._settings = s
        self._client = AsyncOpenAI(api_key=s.openai_api_key, base_url=s.openai_base_url)
        self._model = s.llm_model
        self._embed_model = s.embedding_model

    @property
    def model(self) -> str:
        return self._model

    @property
    def settings(self) -> Settings:
        return self._settings

    async def chat(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.0,
        response_format: dict[str, Any] | None = None,
        max_tokens: int | None = None,
    ) -> tuple[str, dict[str, Any]]:
        """非流式对话。返回 (content, usage)。temperature 默认 0(评估可复现)。

        参数:messages 对话消息列表;response_format 可选(如强制 JSON);
        max_tokens 可选上限。所有底层异常统一包装为 LLMError 抛出。
        usage 为 token 用量 dict(无 usage 时为空 dict),并上报 metrics。
        """
        kwargs: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": temperature,
        }
        if response_format:
            kwargs["response_format"] = response_format
        if max_tokens:
            kwargs["max_tokens"] = max_tokens
        try:
            resp = await self._client.chat.completions.create(**kwargs)
        except Exception as e:
            logger.exception("LLM chat failed")
            raise LLMError(f"LLM 调用失败:{e}") from e

        content = resp.choices[0].message.content or ""
        usage = {}
        if resp.usage:
            usage = {
                "prompt_tokens": resp.usage.prompt_tokens,
                "completion_tokens": resp.usage.completion_tokens,
                "total_tokens": resp.usage.total_tokens,
            }
            get_metrics().record_llm(resp.usage.total_tokens)  # 上报 token 指标
        return content, usage

    async def chat_json(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.0,
    ) -> tuple[Any, dict[str, Any]]:
        """强制 JSON 输出并解析。失败抛 LLMError(调用方可降级)。

        用 response_format={"type":"json_object"} 约束模型输出 JSON;解析失败
        (模型偶发不合规)抛 LLMError,由调用方决定重试/降级。
        """
        content, usage = await self.chat(
            messages,
            temperature=temperature,
            response_format={"type": "json_object"},
        )
        try:
            return json.loads(content), usage
        except json.JSONDecodeError as e:
            raise LLMError(f"LLM 返回非合法 JSON:{e};原文:{content[:200]}") from e

    async def chat_stream(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.0,
    ) -> AsyncIterator[str]:
        """流式对话,逐 token yield。

        stream_options={"include_usage": True} 让流结束时带 usage,便于统计 token;
        增量内容(delta.content)逐段 yield,供上层 SSE 转发。
        """
        try:
            stream = await self._client.chat.completions.create(
                model=self._model,
                messages=messages,
                temperature=temperature,
                stream=True,
                stream_options={"include_usage": True},
            )
        except Exception as e:
            logger.exception("LLM stream failed")
            raise LLMError(f"LLM 流式调用失败:{e}") from e

        total = 0
        async for chunk in stream:
            if chunk.choices and chunk.choices[0].delta.content:
                yield chunk.choices[0].delta.content
            if chunk.usage:
                total = chunk.usage.total_tokens
        if total:
            get_metrics().record_llm(total)

    async def chat_with_tools(
        self,
        messages: list[dict[str, str]],
        tools: list[dict[str, Any]],
        *,
        temperature: float = 0.0,
        tool_choice: str = "auto",
    ) -> tuple[str, list[dict[str, Any]] | None, dict[str, Any]]:
        """带工具的对话(Function Calling)。返回 (content, tool_calls, usage)。

        tool_calls 为 None 或 list[{name, arguments(dict)}]。
        参数:tools 为 OpenAI function schema 列表;tool_choice 控制调用策略
        (auto 由模型决定 / required 强制调用)。LLM 决定调哪个工具、传什么参数。
        """
        kwargs: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": temperature,
            "tools": tools,
            "tool_choice": tool_choice,
        }
        try:
            resp = await self._client.chat.completions.create(**kwargs)
        except Exception as e:
            logger.exception("LLM chat_with_tools failed")
            raise LLMError(f"LLM 工具调用失败:{e}") from e

        msg = resp.choices[0].message
        content = msg.content or ""
        tool_calls = None
        if msg.tool_calls:
            tool_calls = []
            for tc in msg.tool_calls:
                args_raw = tc.function.arguments or "{}"
                try:
                    args = json.loads(args_raw)
                except json.JSONDecodeError:
                    # 参数不是合法 JSON 时保留原文,交给上层兜底(不丢信息)。
                    args = {"_raw": args_raw}
                tool_calls.append({"id": tc.id, "name": tc.function.name, "arguments": args})
        usage = {}
        if resp.usage:
            usage = {
                "prompt_tokens": resp.usage.prompt_tokens,
                "completion_tokens": resp.usage.completion_tokens,
                "total_tokens": resp.usage.total_tokens,
            }
            get_metrics().record_llm(resp.usage.total_tokens)
        return content, tool_calls, usage

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """批量 embedding。空输入返回空列表。异常包装为 LLMError。"""
        if not texts:
            return []
        try:
            resp = await self._client.embeddings.create(
                model=self._embed_model,
                input=texts,
            )
        except Exception as e:
            logger.exception("LLM embed failed")
            raise LLMError(f"Embedding 调用失败:{e}") from e
        return [d.embedding for d in resp.data]


_llm: LLMClient | None = None


def get_llm() -> LLMClient:
    """单例 LLM 客户端。首次调用时初始化(若 key 缺失会抛 LLMError)。"""
    global _llm
    if _llm is None:
        _llm = LLMClient()
    return _llm


def reset_llm() -> None:
    """测试用:重置单例。"""
    global _llm
    _llm = None


def set_llm(client: LLMClient) -> None:
    """测试用:注入 fake client 覆盖单例。"""
    global _llm
    _llm = client
