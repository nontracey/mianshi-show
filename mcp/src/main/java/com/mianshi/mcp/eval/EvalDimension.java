package com.mianshi.mcp.eval;

/** 对照组评测三维分（ADR-4）：功能 / 增益 / 安全；结论必须附证据链与置信度。 */
public enum EvalDimension {
    FUNCTION,   // 契约是否满足
    GAIN,       // 带工具相对基线的增量
    SAFETY      // 执行轨迹中有无高风险事件
}
