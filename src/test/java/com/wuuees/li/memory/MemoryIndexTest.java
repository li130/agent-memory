package com.wuuees.li.memory;

import com.wuuees.li.memory.index.MemoryIndex;
import com.wuuees.li.memory.model.MemoryEntry;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.storage.MemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryIndexTest {

    @TempDir
    Path tempDir;

    MemoryStorage storage;
    MemoryIndex index;

    @BeforeEach
    void setUp() {
        storage = new MemoryStorage(tempDir);
        index = new MemoryIndex(storage);
    }

    @Test
    void shouldGenerateIndexFile() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);

        List<MemoryEntry> entries = new ArrayList<>();
        entries.add(createEntry("user_role", "User role and background", MemoryType.USER, memoryDir));
        entries.add(createEntry("project_release", "Release schedule info", MemoryType.PROJECT, memoryDir));

        index.generate(memoryDir, entries);

        Path indexFile = memoryDir.resolve("MEMORY.md");
        assertTrue(Files.exists(indexFile));

        String content = Files.readString(indexFile);
        assertTrue(content.contains("user_role"));
        assertTrue(content.contains("project_release"));
    }

    @Test
    void shouldTruncateAtLineLimit() {
        // 创建 250 行 —— 应在 200 行处截断
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            lines.add(String.format("- [memory_%03d](memory_%03d.md) — description %d", i, i, i));
        }

        String result = index.truncateAndFormat(lines);

        long actualLines = result.lines().count();
        // 200 条索引 + 2 行警告 + 1 空行
        assertTrue(actualLines <= 203, "期望最多 203 行，实际得到 " + actualLines);
        assertTrue(result.contains("truncated"));
    }

    @Test
    void shouldTruncateAtByteLimit() {
        // 创建带有超长描述的条目
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String longDesc = "x".repeat(500);
            lines.add(String.format("- [memory_%03d](memory_%03d.md) — %s", i, i, longDesc));
        }

        String result = index.truncateAndFormat(lines);

        assertTrue(result.length() <= MemoryIndex.MAX_BYTES + 1000, // allow some slack for warning
                "期望 <= " + MemoryIndex.MAX_BYTES + " 字节，实际得到 " + result.length());
        assertTrue(result.contains("truncated"));
    }

    @Test
    void shouldScanManifest() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);

        // 写入两个记忆文件
        MemoryEntry e1 = createEntry("alpha", "First memory", MemoryType.USER, memoryDir);
        MemoryEntry e2 = createEntry("beta", "Second memory", MemoryType.FEEDBACK, memoryDir);
        Files.writeString(memoryDir.resolve("alpha.md"), e1.toFileContent());
        Files.writeString(memoryDir.resolve("beta.md"), e2.toFileContent());
        Files.writeString(memoryDir.resolve("MEMORY.md"), "");

        var manifest = index.scanManifest(memoryDir);

        assertEquals(2, manifest.size());
        // 按 mtime 降序排列
        assertTrue(manifest.get(0).getMtimeMs() >= manifest.get(1).getMtimeMs());
    }

    @Test
    void shouldReturnEmptyContentWhenNoIndex() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        String content = index.readIndexContent(memoryDir);
        assertEquals("", content);
    }

    private MemoryEntry createEntry(String name, String desc, MemoryType type, Path memoryDir) {
        Path file = memoryDir.resolve(name + ".md");
        return new MemoryEntry(name, desc, type, file, "Content of " + name,
                Instant.now(), Instant.now());
    }
}
