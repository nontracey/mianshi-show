package com.mianshi.mcp.tool;

/**
 * 工具契约：manifest 声明与代码必须一致（CI 校验，ADR-2）。
 * 每个工具合入的硬门槛：timeout / 幂等键（写语义）/ 权限作用域 / 审计事件。
 */
public record ToolContract(
        String name,
        String version,        // manifest 版本，内容变更 → 重扫后才可调用（ADR-3）
        long timeoutMs,
        int maxRetries,        // 仅可重试错误；幂等键保证重试安全
        String requiredScope,  // 权限作用域（概念沿用客户端 ConfirmationToken）
        boolean audit          // 结构化审计事件（可回放）
) {}
