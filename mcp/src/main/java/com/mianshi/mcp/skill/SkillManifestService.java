package com.mianshi.mcp.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill manifest：版本化 + 漂移检测（ADR-3）。
 * 内容变更后未重扫（rescan）的工具禁止调用；版本历史不可变（每次变更新版本号）。
 */
public final class SkillManifestService {

    public record ManifestEntry(String name, int version, String contentHash) {}

    private record State(int version, String contentHash) {}

    private final Map<String, State> registered = new ConcurrentHashMap<>();

    public static String sha256(String content) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 注册或重扫（人工确认内容安全后的"放行"动作）。 */
    public synchronized ManifestEntry rescan(String name, String content) {
        State cur = registered.get(name);
        int nextVersion = cur == null ? 1 : cur.version() + 1;
        State next = new State(nextVersion, sha256(content));
        registered.put(name, next);
        return new ManifestEntry(name, next.version(), next.contentHash());
    }

    /** 调用前校验：未注册或内容漂移 → 拒绝调用。 */
    public boolean isSafeToInvoke(String name, String currentContent) {
        State cur = registered.get(name);
        return cur != null && cur.contentHash().equals(sha256(currentContent));
    }

    public Map<String, ManifestEntry> entries() {
        return registered.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> new ManifestEntry(e.getKey(), e.getValue().version(), e.getValue().contentHash())));
    }
}
