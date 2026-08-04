"""API 路由层:ops(健康/指标)+ rag(问答/入库)+ interview(出题/评估)+ agent(模拟面试)。

职责定位:薄 HTTP 层。只做四件事——
1. 用 app/schemas 的 Pydantic 模型做请求/响应校验;
2. 入口护栏(guardrails 注入检测)与限流由中间件统一处理,
   路由内按需再做业务级校验;
3. 委托给 rag / agent / interview 领域服务,不写业务逻辑;
4. 用 ApiResponse 统一信封包装结果(成功 code=0,异常映射到
   400/500/503 等业务码),并把 traceId 带进响应。

四个路由模块分别挂载:ops(/health、/api/metrics)、
rag(/api/ingest、/api/ask)、
interview(/api/interview/question、/api/interview/evaluate)、
agent(/api/agent/session,SSE 流式)。
"""
