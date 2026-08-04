"""infra 层:LLM 封装、缓存、护栏、可观测、限流。

这是所有业务模块共享的基础设施,与具体业务解耦:
  llm           OpenAI 兼容客户端(chat/chat_json/chat_stream/chat_with_tools/embed)+ token 计数;
  cache         语义缓存(question embedding 相似度命中,省 LLM 调用);
  guardrails    安全护栏(输入注入检测 / PII 脱敏 / 输出 JSON 校验);
  observability traceId(ContextVar 贯穿)+ 进程内指标聚合(token/命中率/延迟);
  ratelimit     每 IP 滑动窗口限流(自带 X-LLM-Key 放行)。
"""
