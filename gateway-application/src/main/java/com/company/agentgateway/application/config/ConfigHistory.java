package com.company.agentgateway.application.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件版本历史（spec §20 版本历史/回滚）。
 *
 * <p>每次被追踪的 JSON 配置写盘后调用 {@link #snapshot}——把当前内容连同
 * 时间戳存入 {@code <name>-history/} 目录（保留最近 N 份，超出删最旧）。
 * 回滚 = 用历史版本覆盖现文件（调用方随后重载内存态）。
 *
 * <p>追踪对象：models.json / api-keys.json（JsonFileModelRegistry、JsonFileApiKeyStore）。
 */
public class ConfigHistory {

    private static final Logger log = LoggerFactory.getLogger(ConfigHistory.class);

    private final Path liveFile;
    private final Path historyDir;
    private final int maxVersions;

    public ConfigHistory(Path liveFile, int maxVersions) {
        this.liveFile = liveFile;
        this.historyDir = liveFile.resolveSibling(liveFile.getFileName() + "-history");
        this.maxVersions = maxVersions;
    }

    /** 版本记录。 */
    public record Version(String file, Instant at, long size) {}

    /** 写盘后调用：把当前 live 文件快照进历史目录。 */
    public void snapshot() {
        try {
            if (!Files.exists(liveFile)) return;
            Files.createDirectories(historyDir);
            String stamp = Long.toString(System.currentTimeMillis());
            Path snap = historyDir.resolve(stamp + ".json");
            Files.copy(liveFile, snap, StandardCopyOption.REPLACE_EXISTING);
            prune();
        } catch (IOException e) {
            log.warn("config snapshot failed for {}: {}", liveFile, e.getMessage());
        }
    }

    /** 列出历史版本（新→旧）。 */
    public List<Version> list() {
        try {
            if (!Files.exists(historyDir)) return List.of();
            try (var files = Files.list(historyDir)) {
                return files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .sorted((a, b) -> b.getFileName().toString()
                                .compareTo(a.getFileName().toString()))
                        .map(f -> new Version(f.getFileName().toString(),
                                Instant.ofEpochMilli(Long.parseLong(
                                        f.getFileName().toString().replace(".json", ""))),
                                sizeOf(f)))
                        .toList();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 回滚到指定版本：历史文件覆盖 live 文件。返回是否成功。 */
    public boolean rollback(String versionFile) {
        try {
            Path snap = historyDir.resolve(versionFile);
            if (!Files.exists(snap)) return false;
            // 回滚前先快照当前（防误操作可再回）
            snapshot();
            Files.copy(snap, liveFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("config {} rolled back to {}", liveFile, versionFile);
            return true;
        } catch (IOException e) {
            log.error("rollback failed: {}", e.getMessage());
            return false;
        }
    }

    private void prune() throws IOException {
        List<Path> versions;
        try (var files = Files.list(historyDir)) {
            // 可变 list：prune 循环里需要 remove(0);Stream.toList() 返不可变 list 会抛 UnsupportedOperationException
            versions = new java.util.ArrayList<>(files
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList());
        }
        // 快照数 = maxVersions + 1（回滚前快照会占一格）；超出删最旧
        while (versions.size() > maxVersions + 1) {
            Files.delete(versions.remove(0));
        }
    }

    /** 读取历史版本文件内容（diff 由上层用 Jackson 解析）。 */
    public String readVersion(String versionFile) {
        try {
            return Files.readString(historyDir.resolve(versionFile));
        } catch (Exception e) {
            return null;
        }
    }

    private static long sizeOf(Path f) {
        try { return Files.size(f); } catch (IOException e) { return -1; }
    }
}
