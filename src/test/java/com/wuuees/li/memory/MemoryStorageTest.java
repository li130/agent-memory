package com.wuuees.li.memory;

import com.wuuees.li.memory.model.MemoryEntry;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.storage.MemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStorageTest {

    @TempDir
    Path tempDir;

    MemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new MemoryStorage(tempDir);
    }

    @Test
    void shouldResolveMemoryDir() {
        Path dir = storage.resolveMemoryDir("/home/user/projects/myproject", null);
        assertNotNull(dir);
        assertTrue(dir.endsWith("memory"));
    }

    @Test
    void shouldSanitizePathWithinBase() {
        Path safeFile = tempDir.resolve("memory/test.md");
        Path result = storage.sanitize(safeFile);
        assertEquals(safeFile.toAbsolutePath().normalize(), result);
    }

    @Test
    void shouldRejectPathEscape() {
        Path escapePath = tempDir.getParent().resolve("escape.md");
        assertThrows(SecurityException.class, () -> storage.sanitize(escapePath));
    }

    @Test
    void shouldAtomicWriteAndRead() throws IOException {
        Path memoryDir = tempDir.resolve("test-project");
        Files.createDirectories(memoryDir);
        Path file = memoryDir.resolve("test-memory.md");

        String content = "---\nname: test\ndescription: A test memory\ntype: user\n---\n\nThis is a test memory.";
        storage.atomicWrite(file, content);

        assertTrue(Files.exists(file));
        assertEquals(content, Files.readString(file));

        // 不应残留 .tmp 文件
        assertFalse(Files.exists(file.resolveSibling("test-memory.md.tmp")));
    }

    @Test
    void shouldReadMemoryFileWithFrontmatter() throws IOException {
        Path file = tempDir.resolve("test.md");
        String content = "---\nname: user_role\ndescription: User is a Java developer\ntype: user\ncreatedAt: 2026-05-03T10:00:00Z\n---\n\nThe user is a senior Java developer.";
        Files.writeString(file, content);

        MemoryEntry entry = storage.readMemoryFile(file);

        assertEquals("user_role", entry.getName());
        assertEquals("User is a Java developer", entry.getDescription());
        assertEquals(MemoryType.USER, entry.getType());
        assertEquals("The user is a senior Java developer.", entry.getContent());
    }

    @Test
    void shouldReadFileWithoutFrontmatter() throws IOException {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "Just some notes without frontmatter.");

        MemoryEntry entry = storage.readMemoryFile(file);

        assertEquals("notes", entry.getName());
        assertEquals(MemoryType.REFERENCE, entry.getType()); // 默认类型
        assertTrue(entry.getContent().contains("Just some notes"));
    }

    @Test
    void shouldListMemoryFilesExcludingIndexAndLogs() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.createFile(memoryDir.resolve("a.md"));
        Files.createFile(memoryDir.resolve("b.md"));
        Files.createFile(memoryDir.resolve("MEMORY.md"));
        Files.createDirectories(memoryDir.resolve("logs"));
        Files.createFile(memoryDir.resolve("logs/2026-05-03.md"));

        var files = storage.listMemoryFiles(memoryDir);
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("a.md")));
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("b.md")));
        assertTrue(files.stream().noneMatch(p -> p.getFileName().toString().equals("MEMORY.md")));
    }

    @Test
    void shouldHashProjectRoot() {
        String hash1 = MemoryStorage.hashProjectRoot("/home/user/project");
        String hash2 = MemoryStorage.hashProjectRoot("/home/user/project");
        assertEquals(hash1, hash2);

        String hash3 = MemoryStorage.hashProjectRoot("/home/user/other");
        assertNotEquals(hash1, hash3);
    }
}
