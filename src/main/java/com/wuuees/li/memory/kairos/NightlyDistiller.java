package com.wuuees.li.memory.kairos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuuees.li.memory.model.MemoryEntry;
import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.model.MemoryStats;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.recall.SideQueryClient;
import com.wuuees.li.memory.service.MemorySystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 夜间蒸馏器。
 *
 * 每天凌晨定时运行（默认 02:00），完整的夜间流程：
 * 1. 蒸馏 KAIROS 日志 → 新建/更新/合并记忆
 * 2. 衰减 + 归档
 * 3. 主动检测（冲突/合并/过期建议）
 * 4. 记忆卫士（死引用检测）
 * 5. 生成 DAILY_BRIEF.md 每日摘要
 */
public class NightlyDistiller {

    private static final Logger log = LoggerFactory.getLogger(NightlyDistiller.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("`([^`]+\\.[a-z]{2,4})`");

    private final KairosLogger kairos;
    private final MemorySystem memorySystem;
    private final SideQueryClient llm;

    public NightlyDistiller(KairosLogger kairos, MemorySystem memorySystem, SideQueryClient llm) {
        this.kairos = kairos;
        this.memorySystem = memorySystem;
        this.llm = llm;
    }

    // ── 定时入口 ──────────────────────────────────────────

    @Scheduled(cron = "${agent-memory.kairos.distill-cron:0 0 2 * * ?}")
    public void scheduledDistill() {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            DistillationResult result = distill(yesterday);
            log.info("蒸馏: {}", result);

            int decayed = memorySystem.decayAll();
            log.info("衰减: {} 条", decayed);

            int archived = memorySystem.archiveDormant();
            log.info("归档: {} 条沉睡", archived);

            List<String> suggestions = detectIssues();
            log.info("主动建议: {} 条", suggestions.size());

            List<String> deadRefs = checkDeadReferences();
            log.info("记忆卫士: {} 条死引用", deadRefs.size());

            writeDailyBrief(yesterday, result, decayed, archived, suggestions, deadRefs);
            log.info("每日摘要已生成");

        } catch (Exception e) {
            log.error("定时蒸馏失败", e);
        }
    }

    // ── 蒸馏 ──────────────────────────────────────────────

    public DistillationResult distill(LocalDate date) throws IOException {
        String logContent = kairos.readLog(date);
        if (logContent.isBlank()) {
            return DistillationResult.empty(date);
        }

        log.info("开始蒸馏 {} 的日志 ({} 字符)...", date, logContent.length());
        String prompt = buildDistillationPrompt(date, logContent);
        String raw;
        try {
            raw = llm.rawCall(prompt, "你是记忆蒸馏助手。");
        } catch (Exception e) {
            log.error("蒸馏 LLM 调用失败: {}", e.getMessage());
            return DistillationResult.failed(date, e.getMessage());
        }
        DistillationResult result = applyDistillation(raw, date);
        log.info("蒸馏完成: 新建{} 更新{} 合并{} 归档{}",
                result.created, result.updated, result.merged, result.archived);
        return result;
    }

    // ── 主动建议 ──────────────────────────────────────────

    public List<String> detectIssues() throws IOException {
        List<String> suggestions = new ArrayList<>();

        // 本地检测：权重过低
        for (MemoryEntry e : memorySystem.loadAllEntries()) {
            if (e.getWeight() < 0.25 && e.getWeight() >= 0.20) {
                suggestions.add("濒危记忆: " + e.getName() + " (weight="
                        + String.format("%.2f", e.getWeight()) + ") 即将被归档");
            }
            if (e.daysSinceRecall() > 60 && e.isActive()) {
                suggestions.add("长期未召回: " + e.getName() + " (" + e.daysSinceRecall()
                        + "天) 建议检查是否仍需保留");
            }
        }

        // LLM 检测冲突和合并建议
        String manifest = buildManifestText();
        if (!manifest.isBlank()) {
            try {
                String raw = llm.rawCall("""
                        你是记忆分析助手。分析以下记忆清单，找出可改进的地方。

                        规则：
                        1. 内容重叠度高的 → type: MERGE, memoryA/B 填要合并的两条记忆名
                        2. 观点矛盾的 → type: CONFLICT, memoryA/B 填矛盾的两条记忆名
                        3. 明显过期的（日期/事件已过去很久）→ type: EXPIRE, memoryA 填该记忆名

                        返回 JSON（没有发现则返回空的 suggestions 数组）：
                        {"suggestions": [{"type": "MERGE|CONFLICT|EXPIRE", "memoryA": "文件名", "memoryB": "文件名或null", "reason": "一句话原因"}]}

                        已有记忆：\n""" + manifest, "你是记忆分析助手。只返回 JSON。");
                JsonNode root = mapper.readTree(extractJson(raw));
                if (root.has("suggestions")) {
                    for (JsonNode s : root.get("suggestions")) {
                        suggestions.add("[" + s.get("type").asText() + "] "
                                + s.get("memoryA").asText()
                                + (s.has("memoryB") && !s.get("memoryB").isNull()
                                ? " ↔ " + s.get("memoryB").asText() : "")
                                + " — " + s.get("reason").asText());
                    }
                }
            } catch (Exception e) {
                log.debug("LLM 检测建议失败: {}", e.getMessage());
            }
        }

        // 落盘
        if (!suggestions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("# 待处理建议\n\n");
            sb.append("生成时间: ").append(LocalDate.now()).append("\n\n");
            for (int i = 0; i < suggestions.size(); i++) {
                sb.append(i + 1).append(". ").append(suggestions.get(i)).append("\n");
            }
            Files.writeString(memorySystem.getMemoryDir().resolve("PENDING_SUGGESTIONS.md"),
                    sb.toString(), StandardCharsets.UTF_8);
        }
        return suggestions;
    }

    // ── 记忆卫士 ──────────────────────────────────────────

    public List<String> checkDeadReferences() throws IOException {
        List<String> deadRefs = new ArrayList<>();
        for (MemoryEntry e : memorySystem.loadAllEntries()) {
            Matcher m = FILE_PATH_PATTERN.matcher(e.getContent() != null ? e.getContent() : "");
            while (m.find()) {
                String path = m.group(1);
                Path resolved = memorySystem.getMemoryDir().resolve(path);
                if (!Files.exists(resolved) && !path.startsWith("http")) {
                    deadRefs.add("在 " + e.getFilePath().getFileName()
                            + " 中引用了不存在的文件: " + path);
                }
            }
        }
        return deadRefs;
    }

    // ── 每日摘要 ──────────────────────────────────────────

    private void writeDailyBrief(LocalDate date, DistillationResult result,
                                  int decayed, int archived,
                                  List<String> suggestions, List<String> deadRefs) throws IOException {
        MemoryStats stats = memorySystem.stats();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(date).append(" 记忆摘要\n\n");

        sb.append("## 蒸馏\n");
        sb.append("- 新建: ").append(result.created).append(" 条\n");
        sb.append("- 更新: ").append(result.updated).append(" 条\n");
        sb.append("- 合并: ").append(result.merged).append(" 条\n");
        sb.append("- 归档: ").append(result.archived).append(" 条\n\n");

        sb.append("## 健康\n");
        sb.append("- ").append(stats.healthSummary()).append("\n");
        sb.append("- 衰减: ").append(decayed).append(" 条\n");
        sb.append("- 归档: ").append(archived).append(" 条沉睡\n\n");

        if (!suggestions.isEmpty()) {
            sb.append("## 待处理建议\n");
            for (String s : suggestions) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }

        if (!deadRefs.isEmpty()) {
            sb.append("## 死引用警告\n");
            for (String d : deadRefs) sb.append("- ").append(d).append("\n");
            sb.append("\n");
        }

        Files.writeString(memorySystem.getMemoryDir().resolve("DAILY_BRIEF.md"),
                sb.toString(), StandardCharsets.UTF_8);
    }

    // ── Prompt ──────────────────────────────────────────────

    private String buildDistillationPrompt(LocalDate date, String logContent) {
        StringBuilder existing = new StringBuilder();
        try {
            for (MemoryManifest m : memorySystem.listManifest()) {
                existing.append("- [").append(m.getPath().getFileName()).append("] ")
                        .append(m.getName()).append(" (").append(m.getType()).append(")")
                        .append(" — ").append(m.getDescription() != null ? m.getDescription() : "");
                existing.append("\n");
            }
        } catch (IOException e) {
            existing.append("（无法读取）\n");
        }

        return """
                你是记忆蒸馏助手。

                ## 已有记忆
                %s

                ## %s 对话日志
                %s

                ## 蒸馏规则
                1. 新事实/偏好/计划 → action: CREATE，同时填 importance (CRITICAL|HIGH|NORMAL|TEMPORAL)
                   - 用户明确说"永远不要"/"绝对不能" → CRITICAL
                   - 长期偏好/习惯 → HIGH
                   - 普通信息 → NORMAL
                   - 临时事件/近期计划 → TEMPORAL
                2. 与已有记忆矛盾或需要更新 → UPDATE（similarTo=已有记忆名）
                3. 多条记忆主题重叠 → MERGE（similarTo=合并目标记忆名）
                4. 识别临时性/已过期信息 → ARCHIVE
                5. 填 tags 数组（中文标签，如 ["java", "测试"]）
                6. 无值得保存的内容 → 返回空 actions 数组

                返回 JSON:
                {"actions": [{"action": "...", "memoryType": "...", "name": "...", "description": "...", "content": "...", "importance": "NORMAL", "tags": [], "similarTo": null, "related": [], "reason": "..."}]}
                """.formatted(existing, date, logContent);
    }

    private String buildManifestText() {
        StringBuilder sb = new StringBuilder();
        try {
            for (MemoryManifest m : memorySystem.listManifest()) {
                sb.append("- [").append(m.getPath().getFileName()).append("] ")
                        .append(m.getName()).append(" (").append(m.getType()).append(") — ")
                        .append(m.getDescription() != null ? m.getDescription() : "");
                sb.append("\n");
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    // ── 应用蒸馏 ──────────────────────────────────────────

    private DistillationResult applyDistillation(String rawJson, LocalDate date) {
        DistillationResult result = new DistillationResult();
        result.date = date;

        try {
            JsonNode root = mapper.readTree(extractJson(rawJson));
            if (!root.has("actions")) return result;

            for (JsonNode action : root.get("actions")) {
                String act = action.has("action") ? action.get("action").asText() : "CREATE";
                String content = field(action, "content");
                String type = field(action, "memoryType");
                String name = field(action, "name");
                String similarTo = field(action, "similarTo");
                String importance = field(action, "importance");

                Map<String, Object> context = new java.util.HashMap<>();
                if (type != null) context.put("type", type);
                if (name != null) context.put("name", name);
                if (importance != null) context.put("importance", importance);

                try {
                    switch (act.toUpperCase()) {
                        case "CREATE", "UPDATE" -> {
                            if (content != null && !content.isBlank()) {
                                memorySystem.save(content, context);
                                result.created++;
                            }
                        }
                        case "MERGE" -> {
                            if (content != null && similarTo != null) {
                                context.put("name", similarTo);
                                memorySystem.save(content, context);
                                result.merged++;
                            }
                        }
                        case "ARCHIVE" -> {
                            if (similarTo != null) result.archived++;
                        }
                    }
                } catch (IOException e) {
                    log.warn("应用蒸馏动作失败: {} -> {}", act, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("解析蒸馏结果失败: {}", e.getMessage());
            result.error = e.getMessage();
        }
        return result;
    }

    private String field(JsonNode node, String name) {
        return node.has(name) && !node.get(name).isNull() ? node.get(name).asText() : null;
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n");
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) trimmed = trimmed.substring(start, end).trim();
        }
        return trimmed;
    }

    public static class DistillationResult {
        public LocalDate date;
        public int created, updated, merged, archived;
        public String error;

        static DistillationResult empty(LocalDate d) { DistillationResult r = new DistillationResult(); r.date = d; return r; }
        static DistillationResult failed(LocalDate d, String err) { DistillationResult r = new DistillationResult(); r.date = d; r.error = err; return r; }

        @Override
        public String toString() {
            if (error != null) return "蒸馏失败(" + date + "): " + error;
            return String.format("蒸馏完成(%s): 新建%d 更新%d 合并%d 归档%d", date, created, updated, merged, archived);
        }
    }
}
