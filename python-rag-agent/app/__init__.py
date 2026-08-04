"""AI 面试陪练服务(Python 版)——「面试智练」系统的服务端 RAG 实现(B 项目)。

项目定位:
    这是一个基于 RAG 知识库的 AI 模拟面试服务。核心能力是把「出题 -> 作答 ->
    LLM 评分 -> 决策追问/建议」的完整面试闭环做成服务端能力,供客户端/Web
    调用。本项目是三个同构实现(B=Python / C / D)中的 Python 版,技术栈为
    FastAPI + 自研 LangGraph 风格状态机 + OpenAI Function Calling。

模块层次(依赖方向自上而下):
    api      对外 HTTP/SSE 路由层(ops 运维 / rag 问答入库 / interview 出题评估 / agent 模拟面试)
      -> 业务层
    rag      RAG 全链路:loader -> splitter -> embedder -> store -> retriever -> generator
    interview 出题(question_gen)与 LLM-as-judge 评估(evaluator)
    agent    LangGraph 风格状态机编排(graph/state/tools)
      -> infra 基础设施
    infra    llm 客户端 / 语义缓存 cache / 安全护栏 guardrails / 可观测 observability / 限流 ratelimit

典型数据流(以 /api/ask 为例):
    请求 -> 中间件(traceId/限流/异常) -> 注入检测(guardrails) -> 语义缓存命中? ->
    混合检索(向量+BM25+RRF) -> 防幻觉 Prompt 生成 -> 写缓存 -> 带来源返回。

对外契约见 docs/00-实现方案-总览.md §4,三语言(B/C/D)严格一致。
"""

__version__ = "0.1.0"
