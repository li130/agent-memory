package com.wuuees.li.memory.injector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.wuuees.li.memory.index.MemoryIndex;
import com.wuuees.li.memory.model.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryInjector {

    private static final Logger log = LoggerFactory.getLogger(MemoryInjector.class);

    // 匹配用户要求忽略记忆的正则模式
    // 匹配: "ignore memory", "don't use the memories", "forget about memories" 等
    // 动词和 "memory/memories" 之间允许最多 3 个插入词
    private static final Pattern IGNORE_PATTERN = Pattern.compile(
            "(?:ignore|don'?t\\s+use|forget|skip|not\\s+(?:use|apply))" +
            "(?:\\s+\\w+){0,3}\\s+(?:the\\s+)?(?:memory|memories)",
            Pattern.CASE_INSENSITIVE);

    private final MemoryIndex index;

    public MemoryInjector(MemoryIndex index) {
        this.index = index;
    }

    /**
     * 构建完整记忆 prompt：MEMORY.md 索引 + 召回的条目正文。
     * 作为 user 角色消息注入，以利用 Prefix Cache 共享。
     */
    public String buildMemoryPrompt(Path memoryDir, String query,
                                     List<MemoryEntry> recalled) throws IOException {
        StringBuilder sb = new StringBuilder();

        // 注入头
        sb.append("# auto memory\n\n");
        sb.append("以下是本次对话相关的记忆内容。");
        sb.append("请在回答中参考这些信息，但引用文件路径和函数名之前请先验证 —— 记忆可能已过时。\n\n");

        // 召回的条目正文（最相关优先）
        if (!recalled.isEmpty()) {
            sb.append("## Relevant Memories\n\n");
            for (MemoryEntry entry : recalled) {
                sb.append("---\n");
                sb.append("### ").append(entry.getName()).append("\n");
                if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
                    sb.append("*").append(entry.getDescription()).append("*\n\n");
                }
                sb.append(entry.getContent()).append("\n\n");
            }
        }

        // MEMORY.md 索引
        String indexContent = index.readIndexContent(memoryDir);
        if (!indexContent.isBlank()) {
            sb.append("## Memory Index\n\n");
            sb.append(indexContent);
        }

        return sb.toString();
    }

    /**
     * 检查用户消息是否包含忽略记忆的指令。
     * 检测到时，整个记忆系统应被完全旁路。
     */
    public boolean hasIgnoreInstruction(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        return IGNORE_PATTERN.matcher(userMessage).find();
    }

    /**
     * 检查多条对话消息中是否包含忽略指令。
     */
    public boolean shouldSuppressMemory(List<String> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return false;
        for (String msg : recentMessages) {
            if (hasIgnoreInstruction(msg)) {
                log.debug("Memory suppressed due to ignore instruction in conversation");
                return true;
            }
        }
        return false;
    }

    /**
     * 仅用 MEMORY.md 索引构建 prompt（不含召回条目）。
     * 当没有找到相关记忆但仍需提供索引时使用。
     */
    public String buildIndexOnlyPrompt(Path memoryDir, String query) throws IOException {
        String indexContent = index.readIndexContent(memoryDir);
        if (indexContent.isBlank()) return "";

        return "# auto memory\n\n" +
                "## Memory Index\n\n" +
                indexContent;
    }
}
