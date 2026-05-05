package com.wuuees.li.memory;

import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.recall.MemoryRecall;
import com.wuuees.li.memory.recall.SideQueryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRecallTest {

    private SideQueryClient llm;
    private MemoryRecall recall;

    @BeforeEach
    void setUp() {
        llm = mock(SideQueryClient.class);
        recall = new MemoryRecall(llm);
    }

    private List<MemoryManifest> makeCandidates() {
        List<MemoryManifest> candidates = new ArrayList<>();
        candidates.add(new MemoryManifest(
                "user_role", "用户是资深 Java 开发者",
                MemoryType.USER, Path.of("user_role.md"), System.currentTimeMillis()));
        candidates.add(new MemoryManifest(
                "feedback_testing", "不要在测试中 mock 数据库",
                MemoryType.FEEDBACK, Path.of("feedback_testing.md"), System.currentTimeMillis() - 1000));
        candidates.add(new MemoryManifest(
                "project_release", "移动端发布冻结从周四开始",
                MemoryType.PROJECT, Path.of("project_release.md"), System.currentTimeMillis() - 2000));
        return candidates;
    }

    @Test
    void shouldRecallViaLLM() {
        var candidates = makeCandidates();
        when(llm.recall(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of("feedback_testing.md", "user_role.md"));

        var opts = new MemoryRecall.RecallOptions().maxResults(3);
        var results = recall.recall("Java 测试怎么做", candidates, opts);

        assertEquals(2, results.size());
        assertEquals("feedback_testing", results.get(0).getName());
        assertEquals("user_role", results.get(1).getName());
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        var candidates = makeCandidates();
        var results = recall.recall("", candidates, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldReturnEmptyForNoCandidates() {
        var results = recall.recall("测试", List.of(), null);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldPassRecentToolsToLLM() {
        var candidates = makeCandidates();
        when(llm.recall(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of("user_role.md"));

        var opts = new MemoryRecall.RecallOptions()
                .recentTools(Set.of("linear"))
                .maxResults(3);

        var results = recall.recall("用户角色", candidates, opts);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldPassAlreadySurfacedToLLM() {
        var candidates = makeCandidates();
        when(llm.recall(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of("project_release.md"));

        var opts = new MemoryRecall.RecallOptions()
                .alreadySurfaced(Set.of("feedback_testing.md"))
                .maxResults(3);

        var results = recall.recall("发布计划", candidates, opts);
        assertFalse(results.isEmpty());
    }
}
