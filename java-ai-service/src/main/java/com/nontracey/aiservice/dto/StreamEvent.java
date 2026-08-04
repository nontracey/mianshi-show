package com.nontracey.aiservice.dto;

/**
 * SSE 流式事件载荷(type + payload),三语言契约一致。
 *
 * <p><b>架构位置</b>:dto 层。由 {@link com.nontracey.aiservice.agent.AgentOrchestrator} 在状态机
 * 各节点产出,经 {@link com.nontracey.aiservice.api.AgentController} 序列化为 JSON 后以
 * {@code event: <type> / data: <payload>} 的 SSE 帧推送给客户端。
 *
 * <p><b>常见 type</b>:retrieve(检索)、question(出题)、answer(模拟作答)、evaluate(评估)、
 * followup(继续追问)、advise(学习建议)、done(结束)、error(异常)。
 *
 * <p><b>同构映射</b>:对应 B 项目 sse-starlette 逐条 yield 的事件字典、D 项目 IAsyncEnumerable 产出的事件。
 *
 * @param type    事件类型(作为 SSE 的 event 字段)
 * @param payload 事件负载(序列化为 JSON 后作为 SSE 的 data 字段)
 */
public record StreamEvent(String type, Object payload) {}
