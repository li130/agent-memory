package com.wuuees.li.memory;

import com.wuuees.li.memory.index.MemoryIndex;
import com.wuuees.li.memory.injector.MemoryInjector;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryInjectorTest {

    @TempDir
    Path tempDir;

    MemoryStorage storage;
    MemoryIndex index;
    MemoryInjector injector;

    @BeforeEach
    void setUp() throws IOException {
        storage = new MemoryStorage(tempDir);
        index = new MemoryIndex(storage);
        injector = new MemoryInjector(index);
    }

    @Test
    void shouldDetectIgnoreInstruction() {
        assertTrue(injector.hasIgnoreInstruction("please ignore the memory for this conversation"));
        assertTrue(injector.hasIgnoreInstruction("don't use memory here"));
        assertTrue(injector.hasIgnoreInstruction("forget about the memories"));
        assertTrue(injector.hasIgnoreInstruction("let's skip memory this time"));
    }

    @Test
    void shouldNotDetectNormalMessage() {
        assertFalse(injector.hasIgnoreInstruction("what does the memory system do?"));
        assertFalse(injector.hasIgnoreInstruction("help me understand memories in this project"));
        assertFalse(injector.hasIgnoreInstruction(""));
        assertFalse(injector.hasIgnoreInstruction(null));
    }

    @Test
    void shouldSuppressOnIgnoreInstruction() {
        assertTrue(injector.shouldSuppressMemory(
                List.of("Hello", "ignore memory for now", "Thanks")));
        assertFalse(injector.shouldSuppressMemory(
                List.of("Hello", "how are you", "Thanks")));
        assertFalse(injector.shouldSuppressMemory(List.of()));
    }

    @Test
    void shouldBuildMemoryPrompt() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"),
                "- [user_role](user_role.md) — User is a Java developer\n");

        MemoryEntry entry = new MemoryEntry(
                "user_role", "User is a senior Java developer",
                MemoryType.USER,
                memoryDir.resolve("user_role.md"),
                "The user has 10 years of Java experience.",
                Instant.now(), Instant.now());

        String prompt = injector.buildMemoryPrompt(memoryDir, "Java developer", List.of(entry));

        assertNotNull(prompt);
        assertTrue(prompt.contains("auto memory"));
        assertTrue(prompt.contains("user_role"));
        assertTrue(prompt.contains("10 years of Java experience"));
        assertTrue(prompt.contains("User is a Java developer"));
    }

    @Test
    void shouldBuildIndexOnlyPrompt() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"),
                "- [user_role](user_role.md) — User is a Java developer\n");

        String prompt = injector.buildIndexOnlyPrompt(memoryDir, "test");

        assertTrue(prompt.contains("auto memory"));
        assertTrue(prompt.contains("Memory Index"));
        assertTrue(prompt.contains("user_role"));
        assertFalse(prompt.contains("Relevant Memories"));
    }

    @Test
    void shouldReturnEmptyForMissingIndex() throws IOException {
        Path memoryDir = tempDir.resolve("empty");
        String prompt = injector.buildIndexOnlyPrompt(memoryDir, "test");
        assertEquals("", prompt);
    }
}
