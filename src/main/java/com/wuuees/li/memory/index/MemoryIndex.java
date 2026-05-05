package com.wuuees.li.memory.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.wuuees.li.memory.model.MemoryEntry;
import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.storage.MemoryStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryIndex {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndex.class);

    public static final int MAX_LINES = 200;
    public static final int MAX_BYTES = 25_000;
    public static final String ENTRYPOINT_NAME = "MEMORY.md";

    private final MemoryStorage storage;

    // volatile 保证线程安全的发布；读取路径无竞争。
    private volatile IndexCache cache;

    public MemoryIndex(MemoryStorage storage) {
        this.storage = storage;
    }

    /**
     * 扫描所有记忆文件，返回清单列表（不含正文）。
     * 当 MEMORY.md 的 mtime 未变时使用缓存。
     */
    public List<MemoryManifest> scanManifest(Path memoryDir) throws IOException {
        Path entrypoint = memoryDir.resolve(ENTRYPOINT_NAME);
        long currentMtime = storage.getLastModifiedMs(entrypoint);

        IndexCache c = this.cache;
        if (c != null && c.memoryDir.equals(memoryDir) && c.mtime == currentMtime) {
            return c.manifest;
        }

        List<Path> files = storage.listMemoryFiles(memoryDir);
        List<MemoryManifest> manifest = new ArrayList<>();
        for (Path file : files) {
            try {
                MemoryEntry entry = storage.readMemoryFile(file);
                manifest.add(new MemoryManifest(
                        entry.getName(),
                        entry.getDescription(),
                        entry.getType(),
                        file,
                        storage.getLastModifiedMs(file)));
            } catch (IOException e) {
                log.warn("读取记忆文件失败: {}", file, e);
            }
        }

        // Sort by mtime descending
        manifest.sort(Comparator.comparingLong(MemoryManifest::getMtimeMs).reversed());

        this.cache = new IndexCache(memoryDir, currentMtime, manifest);
        return manifest;
    }

    /**
     * 根据 MemoryEntry 列表重新生成 MEMORY.md 索引文件。
     */
    public void generate(Path memoryDir, List<MemoryEntry> allEntries) throws IOException {
        storage.ensureMemoryDir(memoryDir);
        Path entrypoint = memoryDir.resolve(ENTRYPOINT_NAME);

        // 按 relevanceScore 降序（权重 × 时效 × 强化），活跃记忆优先
        List<MemoryEntry> sorted = new ArrayList<>(allEntries);
        sorted.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));

        List<String> lines = new ArrayList<>();
        for (MemoryEntry entry : sorted) {
            String fileName = entry.getFilePath() != null
                    ? entry.getFilePath().getFileName().toString()
                    : entry.getName() + ".md";
            String desc = entry.getDescription() != null ? entry.getDescription() : "";
            String line = "- [" + entry.getName() + "](" + fileName + ") — " + desc;
            // Enforce ~150 char per line
            if (line.length() > 156) {
                line = line.substring(0, 153) + "...";
            }
            lines.add(line);
        }

        String content = truncateAndFormat(lines);
        storage.atomicWrite(entrypoint, content);

        // Invalidate cache so next scan picks up the new mtime
        this.cache = null;
        log.debug("Generated {} with {} entries, {} bytes",
                ENTRYPOINT_NAME, lines.size(), content.length());
    }

    /**
     * 应用双重限制截断（200 行 / 25KB）并格式化。
     */
    public String truncateAndFormat(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;

        for (String line : lines) {
            String candidate = line + "\n";
            if (lineCount >= MAX_LINES) {
                appendTruncationWarning(sb, lineCount);
                break;
            }
            if (sb.length() + candidate.length() > MAX_BYTES) {
                appendTruncationWarning(sb, lineCount);
                break;
            }
            sb.append(candidate);
            lineCount++;
        }

        return sb.toString();
    }

    private void appendTruncationWarning(StringBuilder sb, int keptLines) {
        sb.append("\n> ⚠️  Memory index truncated: ")
                .append(keptLines).append(" entries shown. ")
                .append("Older entries were omitted. Consider running /dream to consolidate.\n");
    }

    /**
     * 使缓存失效，强制下次扫描重新读取文件。
     */
    public void invalidateCache() {
        this.cache = null;
    }

    public Path getIndexPath(Path memoryDir) {
        return memoryDir.resolve(ENTRYPOINT_NAME);
    }

    /**
     * 读取 MEMORY.md 全部内容。
     */
    public String readIndexContent(Path memoryDir) throws IOException {
        Path entrypoint = memoryDir.resolve(ENTRYPOINT_NAME);
        if (!Files.exists(entrypoint)) {
            return "";
        }
        return Files.readString(entrypoint, StandardCharsets.UTF_8);
    }

    private static class IndexCache {
        final Path memoryDir;
        final long mtime;
        final List<MemoryManifest> manifest;

        IndexCache(Path memoryDir, long mtime, List<MemoryManifest> manifest) {
            this.memoryDir = memoryDir;
            this.mtime = mtime;
            this.manifest = List.copyOf(manifest);
        }
    }
}
