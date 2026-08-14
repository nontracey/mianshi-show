"""Agent 编排子包:真实 LangGraph StateGraph + Function Calling 工具。

模块构成:
  graph  AgentOrchestrator 状态机编排器(retrieve->ask->simulate->evaluate->decide->followup/advise)
  state  AgentState 贯穿全流程的状态数据
  tools  Function Calling 工具(search_knowledge / save_note)及其 OpenAI schema

状态流转概览:retrieve(带 FC 检索)-> 循环{ ask 出题 -> simulate 模拟回答 ->
evaluate LLM 评分 -> decide 条件边(<70 且有轮次则追问) } -> advise 给建议。

graph.py 使用 LangGraph StateGraph、TypedDict state、显式节点/条件边与
InMemorySaver checkpoint；thread_id 可用于读取单进程内的执行快照。
持久化 PostgreSQL checkpointer 仍属于后续工作，不能宣称已支持多实例恢复。
"""
