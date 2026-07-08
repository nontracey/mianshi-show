package com.nontracey.aiservice.dto;

/** SSE 事件载荷(type + payload),与 B/D 一致。 */
public record StreamEvent(String type, Object payload) {}
