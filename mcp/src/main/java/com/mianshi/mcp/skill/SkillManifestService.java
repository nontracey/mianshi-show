package com.mianshi.mcp.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill manifest：版本化 + 漂移检测（ADR-3）。
 * 内容变更后未重扫（rescan）的工具禁止调用；版本历史不可变（每次变更新版本号）。
 * JSONL 持久化（配置了路径时）：重启后加载历史，版本跨进程只增不减。
 */
public final class SkillManifestService {

    public record ManifestEntry(String name, int version, String contentHash) {}

    private record State(int version, String contentHash) {}

    private final Map<String, State> registered = new ConcurrentHashMap<>();
    private final Path storePath;

    public SkillManifestService() { this.storePath = null; }

    /** 带持久化：启动时加载历史；rescan 追加落盘。 */
    public SkillManifestService(Path storePath) {
        this.storePath = storePath;
        if (storePath != null && Files.exists(storePath)) {
            try {
                for (String line : Files.readAllLines(storePath, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    // 行格式: name|version|contentHash（不可变历史，逐行重放到最新）
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        registered.put(parts[0], new State(Integer.parseInt(parts[1]), parts[2]));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                throw new IllegalStateException("manifest 历史加载失败（不静默）: " + storePath, e);
            }
        }
    }

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
        persist(name, next);
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

    private void persist(String name, State next) {
        if (storePath == null) return;
        try {
            if (storePath.getParent() != null) Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, name + "|" + next.version() + "|" + next.contentHash() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("manifest 落盘失败（不静默）: " + storePath, e);
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, ManifestEntry> sorted(Map<String, ManifestEntry> m) {
        return new LinkedHashMap<>(m);
    }
}
