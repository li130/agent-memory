package com.wuuees.li.memory;

import com.wuuees.li.memory.classifier.MemoryClassifier;
import com.wuuees.li.memory.index.MemoryIndex;
import com.wuuees.li.memory.injector.MemoryInjector;
import com.wuuees.li.memory.model.MemoryEntry;
import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.model.MemoryStats;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.model.SideQueryResult;
import com.wuuees.li.memory.recall.MemoryRecall;
import com.wuuees.li.memory.recall.SideQueryClient;
import com.wuuees.li.memory.service.MemorySystem;
import com.wuuees.li.memory.storage.MemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySystemTest {

    @TempDir
    Path tempDir;

    SideQueryClient llm;
    MemorySystem memorySystem;

    @BeforeEach
    void setUp() throws IOException {
        llm = mock(SideQueryClient.class);

        MemoryStorage storage = new MemoryStorage(tempDir);
        MemoryIndex index = new MemoryIndex(storage);
        MemoryClassifier classifier = new MemoryClassifier(llm);
        MemoryRecall recall = new MemoryRecall(llm);
        MemoryInjector injector = new MemoryInjector(index);

        Path memoryDir = storage.resolveMemoryDir(MemoryStorage.detectProjectRoot(), null);
        storage.ensureMemoryDir(memoryDir);

        memorySystem = new MemorySystem(storage, index, classifier, recall, injector, llm, memoryDir);
    }

    private void mockClassify(MemoryType type, String name, String description, String similarTo) {
        SideQueryResult result = new SideQueryResult();
        result.setAction(SideQueryResult.Action.SAVE);
        result.setMemoryType(type);
        result.setName(name);
        result.setDescription(description);
        result.setSimilarTo(similarTo);
        result.setReason("测试 mock");
        when(llm.classify(any(), anyList())).thenReturn(result);
    }

    @Test
    void shouldSaveAndRecallViaLLM() throws IOException {
        mockClassify(MemoryType.USER, "资深 Java 开发者",
                "用户是资深 Java 开发者，使用 Spring Boot 6 年", null);

        // 保存
        MemoryEntry saved = memorySystem.save(
                "我是一名资深 Java 开发者，使用 Spring Boot 已有 6 年。",
                Map.of());
        assertNotNull(saved);
        assertTrue(Files.exists(saved.getFilePath()));
        assertEquals(MemoryType.USER, saved.getType());

        // mock 召回返回实际保存的文件名
        String savedFileName = saved.getFilePath().getFileName().toString();
        when(llm.recall(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of(savedFileName));

        // 召回
        List<MemoryEntry> recalled = memorySystem.recall("Java Spring Boot 开发者");
        assertFalse(recalled.isEmpty());
    }

    @Test
    void shouldMergeWhenLLMIndicatesSimilar() throws IOException {
        // 先存第一条
        mockClassify(MemoryType.USER, "user_role",
                "用户是资深 Java 开发者", null);
        MemoryEntry first = memorySystem.save("我是一名资深 Java 开发者", Map.of());
        String firstName = first.getFilePath().getFileName().toString();

        // 第二条 LLM 建议合并到第一条
        mockClassify(MemoryType.USER, "user_background",
                "用户也使用 Kubernetes", "user_role");
        when(llm.recall(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of(firstName));

        MemoryEntry second = memorySystem.save("我也使用 Kubernetes 和 Docker", Map.of());

        // 应合并（文件名是第一条的）
        assertTrue(second.getFilePath().toString().contains("user_role"));
    }

    @Test
    void shouldDeleteMemory() throws IOException {
        mockClassify(MemoryType.REFERENCE, "待删除的临时笔记",
                "临时笔记内容", null);

        MemoryEntry saved = memorySystem.save("临时笔记，稍后删除。", Map.of());
        boolean deleted = memorySystem.delete(saved.getName());
        assertTrue(deleted);

        List<MemoryEntry> all = memorySystem.loadAllEntries();
        assertTrue(all.stream().noneMatch(e -> e.getName().equals(saved.getName())));
    }

    @Test
    void shouldListManifest() throws IOException {
        mockClassify(MemoryType.USER, "第一记忆", "第一条", null);
        memorySystem.save("第一条记忆内容。", Map.of());

        mockClassify(MemoryType.PROJECT, "第二记忆", "第二条", null);
        memorySystem.save("第二条记忆，不同内容。", Map.of());

        List<MemoryManifest> manifest = memorySystem.listManifest();
        assertTrue(manifest.size() >= 1);
    }

    @Test
    void shouldBuildMemoryPrompt() throws IOException {
        mockClassify(MemoryType.USER, "资深开发者", "Java 开发者", null);
        memorySystem.save("我是一名资深 Java 开发者。", Map.of());

        when(llm.recall(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of("资深开发者.md"));

        String prompt = memorySystem.buildMemoryPrompt("Java 开发");
        assertNotNull(prompt);
        assertTrue(prompt.contains("auto memory"));
    }

    @Test
    void shouldCheckSuppressMemory() {
        assertTrue(memorySystem.shouldSuppress(
                List.of("Hi", "please ignore memory for now")));
        assertFalse(memorySystem.shouldSuppress(
                List.of("Hi", "help me with memory")));
    }

    @Test
    void shouldReinforceAndDecayMemory() throws IOException {
        mockClassify(MemoryType.USER, "测试记忆", "生命周期测试", null);
        MemoryEntry saved = memorySystem.save("生命周期测试内容。", Map.of());
        assertEquals(0.7, saved.getWeight(), 0.01);

        // 强化
        memorySystem.reinforce(saved.getFilePath().getFileName().toString());
        MemoryEntry r = memorySystem.loadAllEntries().stream()
                .filter(e -> e.getName().equals("测试记忆"))
                .findFirst().orElseThrow();
        assertEquals(0.72, r.getWeight(), 0.01);
        assertEquals(1, r.getReinforced());
    }

    @Test
    void shouldArchiveDormantMemory() throws IOException {
        mockClassify(MemoryType.USER, "临时笔记", "很快被遗忘", null);
        MemoryEntry saved = memorySystem.save("临时记忆。", Map.of());
        saved.setWeight(0.15);
        Files.writeString(saved.getFilePath(), saved.toFileContent());

        int archived = memorySystem.archiveDormant();
        assertTrue(archived >= 1);
    }

    @Test
    void shouldSyncBidirectionalRelated() throws IOException {
        mockClassify(MemoryType.USER, "记忆A", "主记忆", null);
        MemoryEntry a = memorySystem.save("A 的内容。", Map.of());

        mockClassify(MemoryType.REFERENCE, "记忆B", "被关联", null);
        MemoryEntry b = memorySystem.save("B 的内容。", Map.of());

        a.setRelated(List.of(b.getFilePath().getFileName().toString()));
        Files.writeString(a.getFilePath(), a.toFileContent());
        memorySystem.syncBidirectionalRelated(a);

        MemoryEntry b2 = memorySystem.loadAllEntries().stream()
                .filter(e -> e.getName().equals("记忆B"))
                .findFirst().orElseThrow();
        assertTrue(b2.getRelated().contains(a.getFilePath().getFileName().toString()));
    }

    @Test
    void shouldCleanupDeadReferences() throws IOException {
        mockClassify(MemoryType.USER, "幸存者", "引用清理", null);
        MemoryEntry s = memorySystem.save("存活。", Map.of());
        s.setRelated(new ArrayList<>(List.of("ghost_file.md")));
        Files.writeString(s.getFilePath(), s.toFileContent());

        memorySystem.cleanupDeadReferences("ghost_file.md");
        MemoryEntry after = memorySystem.loadAllEntries().stream()
                .filter(e -> e.getName().equals("幸存者"))
                .findFirst().orElseThrow();
        assertFalse(after.getRelated().contains("ghost_file.md"));
    }

    @Test
    void shouldReturnStats() throws IOException {
        mockClassify(MemoryType.USER, "用户A", "用户记忆", null);
        memorySystem.save("用户 A 的记忆。", Map.of());
        mockClassify(MemoryType.FEEDBACK, "反馈B", "反馈记忆", null);
        memorySystem.save("反馈 B 的记忆。", Map.of());

        MemoryStats stats = memorySystem.stats();
        assertTrue(stats.totalActive() >= 2);
        assertTrue(stats.byType().containsKey(MemoryType.USER));
        assertTrue(stats.avgWeight() > 0);
        assertNotNull(stats.healthSummary());
        System.out.println("[stats] " + stats.healthSummary());
    }

    @Test
    void shouldListArchived() throws IOException {
        mockClassify(MemoryType.USER, "待归档", "很快就沉睡", null);
        MemoryEntry s = memorySystem.save("即将沉睡。", Map.of());
        s.setWeight(0.15);
        Files.writeString(s.getFilePath(), s.toFileContent());

        memorySystem.archiveDormant();
        List<MemoryEntry> archived = memorySystem.listArchived();
        assertTrue(archived.stream().anyMatch(e -> e.getName().equals("待归档")));
    }

    @Test
    void shouldReturnSummary() throws Exception {
        mockClassify(MemoryType.USER, "主题", "记忆导览测试", null);
        memorySystem.save("主题记忆内容。", Map.of());

        when(llm.rawCall(any(), any()))
                .thenReturn("你可能想回顾：① 主题（记忆导览测试）");

        String s = memorySystem.summary("测试查询");
        assertNotNull(s);
        assertTrue(s.contains("主题"));
    }

    @Test
    void shouldReturnEmptySummaryOnError() throws Exception {
        when(llm.rawCall(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        String s = memorySystem.summary("查询");
        assertEquals("", s);
    }

    @Test
    void shouldSetImportanceAndListByTag() throws IOException {
        mockClassify(MemoryType.USER, "记忆X", "标签测试", null);
        MemoryEntry saved = memorySystem.save("通过 context 设定 importance。",
                Map.of("importance", "CRITICAL"));
        assertEquals(MemoryEntry.Importance.CRITICAL, saved.getImportance());
        assertEquals(0.95, saved.getWeight(), 0.01);

        // 手动加标签
        saved.getTags().add("java");
        saved.getTags().add("test");
        Files.writeString(saved.getFilePath(), saved.toFileContent());

        List<MemoryEntry> byTag = memorySystem.listByTag("java");
        assertTrue(byTag.stream().anyMatch(e -> e.getName().equals("记忆X")));
    }

    @Test
    void shouldSnapshotVersionOnMerge() throws IOException {
        mockClassify(MemoryType.USER, "版本测试", "初始版本", null);
        MemoryEntry v1 = memorySystem.save("第一版内容。", Map.of());

        mockClassify(MemoryType.USER, "版本测试", "更新版本", "版本测试");
        memorySystem.save("第二版内容，合并到第一版。", Map.of());

        List<Path> versions = memorySystem.listVersions(v1.getFilePath().getFileName().toString());
        assertTrue(versions.size() >= 1, "合并时应保留旧版本快照");
    }

    @Test
    void shouldReturnTagStats() throws IOException {
        mockClassify(MemoryType.USER, "标签A", "测试", null);
        MemoryEntry a = memorySystem.save("内容A", Map.of());
        a.setTags(new ArrayList<>(List.of("java", "web")));
        Files.writeString(a.getFilePath(), a.toFileContent());

        Map<String, Integer> stats = memorySystem.tagStats();
        assertNotNull(stats);
    }
}
