package com.mianshi.mcp.eval;

import java.nio.file.Path;
import java.util.List;

/** 对照组评测协议（ADR-4）：同一题集跑"带工具/不带工具"两组，输出三维分 + 证据链 + 置信度。 */
public final class EvalProtocol {

    public record GoldenItem(String id, String question, List<String> expectKeywords, String sourceDoc) {}

    public record ItemResult(String itemId, boolean functionPass, boolean gainHit, boolean safetyPass,
                             String baseAnswer, String augmentedAnswer, String evidence) {}

    public record RunReport(int items, long functionScore, long gainScore, long safetyScore,
                            List<ItemResult> details, List<String> badCases, String confidence) {}

    /** 确定性应答器：有检索取 top1 片段，无检索只能答"依据不足"。可复现，可当门禁。 */
    public interface Responder {
        record Response(String answer, String evidenceSource, boolean schemaValid) {}
        Response answer(String question, boolean withTools);
    }

    /** 工作区隔离：每次运行独立 runId 目录，失败回滚（删除），成功保留。 */
    public interface Workspace {
        Path prepare(String runId);
        void rollback(String runId);
        Path root();
    }
}
