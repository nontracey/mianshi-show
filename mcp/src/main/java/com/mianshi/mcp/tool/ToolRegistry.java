package com.mianshi.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：契约即注册。代码与 manifest 不一致 = 缺陷（ADR-2）。
 * T2 实现项：与 Skill 层的版本哈希联动（漂移检测，ADR-3）。
 */
public final class ToolRegistry {

    public enum Plan { QUESTION_SEARCH, EVAL_RUN, COACH_SESSION }

    private final Map<Plan, ToolContract> registered = new ConcurrentHashMap<>();

    public void register(ToolContract contract) {
        // TODO(T2): 校验 timeout>0、audit=true、scope 非空（ADR-2 硬门槛）
        registered.put(Plan.valueOf(contract.name()), contract);
    }

    public List<ToolContract> contracts() {
        return List.copyOf(registered.values());
    }
}
