"""Agent 编排:LangGraph 风格状态图 + Function Calling 工具。

为避免 langgraph 网络安装受限,这里实现一个等价的轻量异步状态机:
节点是 async 函数,条件边按 state 字段路由。接口设计与 LangGraph 一致,
后续装 langgraph 后可平滑替换。
"""
