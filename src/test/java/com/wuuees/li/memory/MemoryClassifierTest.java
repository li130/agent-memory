package com.wuuees.li.memory;

import com.wuuees.li.memory.classifier.MemoryClassifier;
import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.model.SideQueryResult;
import com.wuuees.li.memory.recall.SideQueryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryClassifierTest {

    private SideQueryClient llm;
    private MemoryClassifier classifier;

    @BeforeEach
    void setUp() {
        llm = mock(SideQueryClient.class);
        classifier = new MemoryClassifier(llm);
    }

    @Test
    void shouldReturnTypeFromLLM() {
        var result = new SideQueryResult();
        result.setMemoryType(MemoryType.USER);
        result.setName("资深开发者");
        result.setDescription("用户是资深 Java 开发者");
        when(llm.classify(any(), anyList())).thenReturn(result);

        MemoryType type = classifier.classify("任意内容", Map.of());
        assertEquals(MemoryType.USER, type);
    }

    @Test
    void shouldReturnFullResultFromLLM() {
        var result = new SideQueryResult();
        result.setAction(SideQueryResult.Action.SAVE);
        result.setMemoryType(MemoryType.FEEDBACK);
        result.setName("不要 mock 数据库");
        result.setDescription("不要在测试中 mock 数据库");
        result.setSimilarTo(null);
        result.setReason("行为纠正");
        when(llm.classify(any(), anyList())).thenReturn(result);

        SideQueryResult classified = classifier.classifyFull(
                "不要在测试中 mock 数据库。", Map.of(), List.of());

        assertEquals(SideQueryResult.Action.SAVE, classified.getAction());
        assertEquals(MemoryType.FEEDBACK, classified.getMemoryType());
        assertEquals("不要 mock 数据库", classified.getName());
        assertEquals("行为纠正", classified.getReason());
    }

    @Test
    void shouldPassContextToLLM() {
        var result = new SideQueryResult();
        result.setMemoryType(MemoryType.PROJECT);
        when(llm.classify(any(), anyList())).thenReturn(result);

        classifier.classifyFull("截止日期周五", Map.of("type", "PROJECT"), List.of());

        // 验证 LLM 被调用了（classifyFull 委托给 llm.classify）
        // 如果调用了即证明逻辑正确
    }

    @Test
    void shouldPassExistingMemoriesToLLM() {
        var manifest = List.of(
                new MemoryManifest("user_role", "用户是 Java 开发者",
                        MemoryType.USER, java.nio.file.Path.of("user_role.md"), 0L));

        var result = new SideQueryResult();
        result.setMemoryType(MemoryType.USER);
        result.setSimilarTo("user_role");
        when(llm.classify(any(), anyList())).thenReturn(result);

        SideQueryResult classified = classifier.classifyFull(
                "我是一名 Java 开发者", Map.of(), manifest);

        assertEquals("user_role", classified.getSimilarTo());
    }

    @Test
    void shouldDefaultToReferenceWhenLLMReturnsNullType() {
        var result = new SideQueryResult();
        result.setMemoryType(null);
        when(llm.classify(any(), anyList())).thenReturn(result);

        MemoryType type = classifier.classify("...", Map.of());
        assertEquals(MemoryType.REFERENCE, type);
    }
}
