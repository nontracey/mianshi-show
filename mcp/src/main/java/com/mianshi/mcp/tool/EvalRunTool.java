package com.mianshi.mcp.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.mianshi.mcp.cost.CostLedger;
import com.mianshi.mcp.eval.EvalHarness;
import com.mianshi.mcp.eval.EvalProtocol;
import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.governance.GovernanceExecutor;

/**
 * 工具 2：评测执行。跑对照组（带工具/不带），产出三维分 + bad case + 置信度；
 * 工作区按 runId 隔离，失败回滚。评测集样本小是已知短板（ADR-7，先自己说）。
 */
@Component
public class EvalRunTool {

    private final EvalHarness harness;
    private final GovernanceExecutor governance;
    private final AuditLog audit;
    private final CostLedger cost;

    public EvalRunTool(EvalHarness harness, GovernanceExecutor governance, AuditLog audit, CostLedger cost) {
        this.harness = harness;
        this.governance = governance;
        this.audit = audit;
        this.cost = cost;
    }

    /** 默认黄金集：来源为自建对照集的结构断言（样本小是已知短板，主动声明）。 */
    private static final List<EvalProtocol.GoldenItem> GOLDEN = List.of(
            new EvalProtocol.GoldenItem("G1", "上线判据和黄金集发布门是怎么设计的？",
                    List.of("黄金集", "发布门"), "golden-set"),
            new EvalProtocol.GoldenItem("G2", "混合检索里 RRF 起什么作用？",
                    List.of("RRF", "混合检索"), "rrf-hybrid"),
            new EvalProtocol.GoldenItem("G3", "上下文超预算时怎么裁剪？",
                    List.of("预算", "裁"), "context-budget"));

    @Tool(description = "运行对照组评测：同一题集分别以带工具/不带工具两种方式应答，输出功能/增益/安全三维分与 bad case")
    public String runEval(
            @ToolParam(required = false, description = "运行标识，唯一；重复运行会拒绝（证据不可静默改写）") String runId) {
        // 外部验收发现（2026-09-22）：缺参时 spring-ai M3 返回空 text 块（isError 无内容），
        // 严格客户端拒收。参数改非必填 + 工具内显式校验，错误以结构化 JSON 返回。
        if (runId == null || runId.isBlank()) {
            return "{\"error\":\"missing_runId\",\"hint\":\"runId 必填且唯一：重复运行会拒绝（证据不可静默改写）\"}";
        }
        var contract = new ToolContract("EVAL_RUN", "v1", 15000, 0, "eval:execute", true);
        Object result = governance.execute(contract, "run:" + runId, () ->
                harness.run(runId, GOLDEN, harness.deterministicResponder()));
        var report = (EvalProtocol.RunReport) result;
        cost.record("eval-run", 0, 0);
        return ("{\"runId\":\"%s\",\"items\":%d,\"functionScore\":%d,\"gainScore\":%d,\"safetyScore\":%d,"
                + "\"confidence\":\"%s\",\"badCases\":[%s]}")
                .formatted(runId, report.items(), report.functionScore(), report.gainScore(),
                        report.safetyScore(), report.confidence(),
                        report.badCases().stream()
                                .map(b -> "\"" + b.replace("\"", "'") + "\"")
                                .collect(java.util.stream.Collectors.joining(",")));
    }
}
