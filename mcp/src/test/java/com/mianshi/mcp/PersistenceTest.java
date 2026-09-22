package com.mianshi.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mianshi.mcp.governance.AuditLog;
import com.mianshi.mcp.skill.SkillManifestService;

/** 持久化语义：审计可回放、manifest 版本跨进程只增不减（ADR-2/ADR-3 的事实基础）。 */
class PersistenceTest {

    @Test
    void auditEventsSurviveRestart(@TempDir Path tmp) throws Exception {
        Path sink = tmp.resolve("audit.jsonl");
        AuditLog first = new AuditLog(sink);
        first.record("T", "call", "success", 5, java.util.Map.of("k", "v"));
        first.record("T", "call", "failure", 7, null);

        AuditLog reopened = new AuditLog(sink); // 模拟重启后从 JSONL 读回
        var lines = reopened.replayFromFile();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"tool\":\"T\"").contains("success");
        assertThat(lines.get(1)).contains("failure");
    }

    @Test
    void manifestVersionIsMonotonicAcrossRestart(@TempDir Path tmp) {
        Path store = tmp.resolve("manifest.jsonl");
        SkillManifestService first = new SkillManifestService(store);
        first.rescan("t", "v1");
        first.rescan("t", "v2");

        SkillManifestService reopened = new SkillManifestService(store); // 重启加载历史
        var entry = reopened.rescan("t", "v3");
        assertThat(entry.version()).isEqualTo(3); // 跨进程只增不减
        assertThat(reopened.isSafeToInvoke("t", "v2")).isFalse(); // 旧内容视为漂移
        assertThat(reopened.isSafeToInvoke("t", "v3")).isTrue();
    }
}
