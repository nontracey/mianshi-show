"""Agent 编排子包:LangGraph 风格状态图 + Function Calling 工具。

模块构成:
  graph  AgentOrchestrator 状态机编排器(retrieve->ask->simulate->evaluate->decide->followup/advise)
  state  AgentState 贯穿全流程的状态数据
  tools  Function Calling 工具(search_knowledge / save_note)及其 OpenAI schema

状态流转概览:retrieve(带 FC 检索)-> 循环{ ask 出题 -> simulate 模拟回答 ->
evaluate LLM 评分 -> decide 条件边(<70 且有轮次则追问) } -> advise 给建议。

为避免 langgraph 网络安装受限,这里实现一个等价的轻量异步状态机:
节点是 async 函数,条件边按 state 字段路由。接口设计与 LangGraph 一致,
后续装 langgraph 后可平滑替换。
"""
