package com.wuuees.li.memory.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.wuuees.li.memory.storage.MemoryStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 中心门面，统一编排所有记忆系统模块。
 *
 * <h3>写入流</h3>
 * <pre>
 *   classifyFull（LLM）→ findSimilar → atomicWrite → regenerateIndex
 * </pre>
 *
 * <h3>召回流</h3>
 * <pre>
 *   scanManifest → recall（LLM 侧查询）→ loadEntries → buildPrompt
 * </pre>
 */
public class MemorySystem {

    private static final Logger log = LoggerFactory.getLogger(MemorySystem.class);

    private final MemoryStorage storage;
    private final MemoryIndex index;
    private final MemoryClassifier classifier;
    private final MemoryRecall recall;
    private final MemoryInjector injector;
    private final SideQueryClient llm;

    private final Path memoryDir;

    public MemorySystem(MemoryStorage storage, MemoryIndex index,
                        MemoryClassifier classifier, MemoryRecall recall,
                        MemoryInjector injector, SideQueryClient llm,
                        Path memoryDir) {
        this.storage = storage;
        this.index = index;
        this.classifier = classifier;
        this.recall = recall;
        this.injector = injector;
        this.llm = llm;
        this.memoryDir = memoryDir;
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    // ── Write ──────────────────────────────────────────────

    /**
     * 保存一条记忆。完整写入流程：
     * classifyFull（LLM 分类+命名+去重）→ write → regenerateIndex。
     */
    public MemoryEntry save(String content, Map<String, Object> context) throws IOException {
        storage.ensureMemoryDir(memoryDir);

        // 1. LLM 分类（含 name、description、去重建议）
        List<MemoryManifest> manifest = index.scanManifest(memoryDir);
        SideQueryResult classified = classifier.classifyFull(content, context, manifest);

        String name = classified.getName();
        String description = classified.getDescription();
        MemoryType type = classified.getMemoryType();

        Instant now = Instant.now();
        MemoryEntry newEntry = new MemoryEntry(name, description, type, null, content, now, now);

        // 设定重要性（来自 LLM 判定或 context 传参）
        if (context != null && context.containsKey("importance")) {
            try {
                newEntry.setImportance(MemoryEntry.Importance.valueOf(
                        context.get("importance").toString().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        // 2. 用 LLM similarTo 去重
        List<MemoryEntry> existingEntries = loadAllEntries();
        Optional<MemoryEntry> similar = Optional.empty();
        if (classified.getSimilarTo() != null) {
            String target = classified.getSimilarTo();
            similar = existingEntries.stream()
                    .filter(e -> e.getName().equals(target) ||
                            target.equalsIgnoreCase(sanitizeFileName(e.getName())))
                    .findFirst();
        }

        MemoryEntry saved;
        if (similar.isPresent()) {
            MemoryEntry existing = similar.get();
            // 变更历史：旧版本快照
            snapshotVersion(existing);
            existing.setContent(existing.getContent() + "\n\n" + content);
            existing.setUpdatedAt(now);
            existing.setDescription(mergeDescription(existing.getDescription(), description));
            saved = writeEntry(existing);
            log.info("合并到已有记忆: {}", existing.getName());
        } else {
            saved = writeEntry(newEntry);
            log.info("新建记忆: {} (type={})", saved.getName(), saved.getType());
        }

        // 3. 双向关联同步
        syncBidirectionalRelated(saved);

        // 4. 重建索引
        index.generate(memoryDir, loadAllEntries());

        return saved;
    }

    private MemoryEntry writeEntry(MemoryEntry entry) throws IOException {
        Path filePath = memoryDir.resolve(sanitizeFileName(entry.getName()) + ".md");
        entry.setFilePath(filePath);
        String fileContent = entry.toFileContent();
        storage.atomicWrite(filePath, fileContent);
        return entry;
    }

    // ── Recall ─────────────────────────────────────────────

    /**
     * 根据查询召回相关记忆，返回完整的 MemoryEntry 对象。
     */
    public List<MemoryEntry> recall(String query) throws IOException {
        return recall(query, null, Set.of(), Set.of());
    }

    /**
     * 带完整选项的召回。
     */
    public List<MemoryEntry> recall(String query, MemoryRecall.RecallOptions options,
                                     Set<String> recentTools, Set<String> alreadySurfaced)
            throws IOException {
        // 1. 扫描清单
        List<MemoryManifest> manifest = index.scanManifest(memoryDir);

        // 2. 召回
        MemoryRecall.RecallOptions opts = options != null ? options
                : new MemoryRecall.RecallOptions()
                    .recentTools(recentTools)
                    .alreadySurfaced(alreadySurfaced);
        List<MemoryManifest> relevant = recall.recall(query, manifest, opts);

        // 3. 加载完整条目
        List<MemoryEntry> entries = new ArrayList<>();
        for (MemoryManifest m : relevant) {
            try {
                entries.add(storage.readMemoryFile(m.getPath()));
            } catch (IOException e) {
                log.warn("读取召回记忆文件失败: {}", m.getPath(), e);
            }
        }
        return entries;
    }

    // ── Recall with related-chain expansion ─────────────────

    /**
     * 召回 + 沿 related 链扩展。
     * 先从 LLM 召回 Top-N，再逐个读取其 related 字段，拉入关联记忆。
     * 返回按依赖顺序排列的完整条目列表（被关联的排在后面）。
     */
    public List<MemoryEntry> recallWithRelated(String query) throws IOException {
        return recallWithRelated(query, null, Set.of(), Set.of(), -1);
    }

    /**
     * 召回 + related 链扩展，可控制扩展深度。
     *
     * @param query          查询文本
     * @param options        召回选项
     * @param recentTools    最近使用的工具（用于去噪）
     * @param alreadySurfaced 已展示过的路径（去重）
     * @param maxDepth       最大扩展深度（-1 = 追到底，1 = 一层，2 = 两层...）
     */
    public List<MemoryEntry> recallWithRelated(String query, MemoryRecall.RecallOptions options,
                                                Set<String> recentTools, Set<String> alreadySurfaced,
                                                int maxDepth) throws IOException {
        // 1. 基础召回
        List<MemoryEntry> recalled = recall(query, options, recentTools, alreadySurfaced);

        // 2. 沿 related 链展开
        Set<String> seen = new LinkedHashSet<>();
        List<MemoryEntry> result = new ArrayList<>();

        for (MemoryEntry entry : recalled) {
            addWithRelated(entry, seen, result, maxDepth, 0);
        }

        log.info("召回+关联扩展: 直接召回 {} 条 → 扩展后共 {} 条 (maxDepth={})",
                recalled.size(), result.size(), maxDepth);
        return result;
    }

    private void addWithRelated(MemoryEntry entry, Set<String> seen,
                                 List<MemoryEntry> result, int maxDepth, int currentDepth)
            throws IOException {
        String key = entry.getFilePath() != null
                ? entry.getFilePath().getFileName().toString()
                : entry.getName();
        if (!seen.add(key)) return; // 已处理过，避免循环引用

        result.add(entry);

        // -1 = 无限深度，追到底
        if (maxDepth >= 0 && currentDepth >= maxDepth) return;
        if (entry.getRelated() == null || entry.getRelated().isEmpty()) return;

        // 加载 related 指向的文件
        for (String relatedFile : entry.getRelated()) {
            if (seen.contains(relatedFile)) continue;
            try {
                Path relatedPath = memoryDir.resolve(relatedFile);
                if (storage.exists(relatedPath)) {
                    MemoryEntry related = storage.readMemoryFile(relatedPath);
                    addWithRelated(related, seen, result, maxDepth, currentDepth + 1);
                }
            } catch (IOException e) {
                log.warn("无法加载关联记忆: {}", relatedFile);
            }
        }
    }

    // ── 生命周期管理 ──────────────────────────────────────

    /**
     * 强化一条记忆：每次被正确召回确认时调用，weight += 0.02，reinforced++。
     */
    public void reinforce(String fileName) throws IOException {
        Path filePath = memoryDir.resolve(fileName);
        if (!storage.exists(filePath)) return;
        MemoryEntry entry = storage.readMemoryFile(filePath);
        entry.reinforce();
        entry.touch();
        storage.atomicWrite(filePath, entry.toFileContent());
        log.debug("强化记忆: {} (weight={})", entry.getName(), String.format("%.2f", entry.getWeight()));
    }

    /**
     * 对所有记忆执行一次衰减。
     * 通常由 NightlyDistiller 调用（每天一次），或每周手动触发。
     */
    public int decayAll() throws IOException {
        List<MemoryEntry> all = loadAllEntries();
        int decayed = 0;
        for (MemoryEntry entry : all) {
            // 今天被召回过的不衰减（活跃保护）
            if (entry.getLastRecalled() != null
                    && entry.daysSinceRecall() == 0) continue;
            entry.decay();
            entry.touch();
            storage.atomicWrite(entry.getFilePath(), entry.toFileContent());
            decayed++;
        }
        index.generate(memoryDir, loadAllEntries());
        log.info("衰减完成: {} 条记忆已衰减", decayed);
        return decayed;
    }

    /**
     * 归档沉睡记忆：weight < 0.2 的记忆移到 .archive/ 目录。
     * 不删除，只是从活跃区降级。
     */
    public int archiveDormant() throws IOException {
        List<MemoryEntry> all = loadAllEntries();
        Path archiveDir = memoryDir.resolve(".archive");
        Files.createDirectories(archiveDir);

        int archived = 0;
        for (MemoryEntry entry : all) {
            if (entry.isDormant()) {
                Path target = archiveDir.resolve(entry.getFilePath().getFileName());
                Files.move(entry.getFilePath(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                archived++;
                log.info("归档: {} → .archive/ (weight={})",
                        entry.getName(), String.format("%.2f", entry.getWeight()));
            }
        }
        if (archived > 0) {
            index.invalidateCache();
            index.generate(memoryDir, loadAllEntries());
        }
        return archived;
    }

    /**
     * 强制让沉睡记忆苏醒：手动设回 weight=0.5，移回活跃区。
     */
    public MemoryEntry awaken(String name) throws IOException {
        Path archivePath = memoryDir.resolve(".archive").resolve(sanitizeFileName(name) + ".md");
        if (!storage.exists(archivePath)) {
            Path activePath = memoryDir.resolve(sanitizeFileName(name) + ".md");
            if (!storage.exists(activePath)) return null;
            archivePath = activePath;
        }

        MemoryEntry entry = storage.readMemoryFile(archivePath);
        entry.setWeight(0.5);
        entry.setReinforced(0);
        entry.setLastRecalled(null);
        entry.touch();

        Path activePath = memoryDir.resolve(archivePath.getFileName());
        storage.atomicWrite(activePath, entry.toFileContent());
        if (!archivePath.equals(activePath)) Files.deleteIfExists(archivePath);

        index.generate(memoryDir, loadAllEntries());
        log.info("苏醒: {} (weight=0.50)", entry.getName());
        return entry;
    }

    // ── 双向关联同步 ──────────────────────────────────────

    /**
     * 保存后同步双向关联：A.related=[B] → 自动给 B.related 补上 A。
     */
    public void syncBidirectionalRelated(MemoryEntry saved) throws IOException {
        if (saved.getRelated() == null || saved.getRelated().isEmpty()) return;
        String myFile = saved.getFilePath().getFileName().toString();

        for (String target : saved.getRelated()) {
            Path targetPath = memoryDir.resolve(target);
            if (!storage.exists(targetPath)) continue;

            MemoryEntry targetEntry = storage.readMemoryFile(targetPath);
            if (targetEntry.getRelated() == null) targetEntry.setRelated(new ArrayList<>());
            if (!targetEntry.getRelated().contains(myFile)) {
                targetEntry.getRelated().add(myFile);
                targetEntry.touch();
                storage.atomicWrite(targetPath, targetEntry.toFileContent());
                log.debug("双向关联: {} ←→ {}", myFile, target);
            }
        }
    }

    /**
     * 删除记忆时清理所有指向它的 related 引用。
     */
    public int cleanupDeadReferences(String deletedFileName) throws IOException {
        List<MemoryEntry> all = loadAllEntries();
        int cleaned = 0;
        for (MemoryEntry entry : all) {
            if (entry.getRelated() != null && entry.getRelated().remove(deletedFileName)) {
                entry.touch();
                storage.atomicWrite(entry.getFilePath(), entry.toFileContent());
                cleaned++;
            }
        }
        if (cleaned > 0) log.info("清理死链: 从 {} 条记忆中移除 {}", cleaned, deletedFileName);
        return cleaned;
    }

    // ── 可观测性 & 导览 ──────────────────────────────────

    /**
     * 记忆系统统计快照。
     */
    public MemoryStats stats() throws IOException {
        List<MemoryEntry> active = loadAllEntries();
        List<MemoryEntry> archived = listArchived();
        LocalDate now = LocalDate.now();

        Map<MemoryType, Integer> byType = new java.util.HashMap<>();
        double totalWeight = 0;
        int nearDormant = 0, createdThisMonth = 0, totalReinforced = 0;
        String highestName = null, lowestName = null;
        double highestW = 0, lowestW = 1;

        for (MemoryEntry e : active) {
            byType.merge(e.getType(), 1, Integer::sum);
            double w = e.getWeight();
            totalWeight += w;
            totalReinforced += e.getReinforced();
            if (w < 0.25) nearDormant++;
            if (w > highestW) { highestW = w; highestName = e.getName(); }
            if (w < lowestW) { lowestW = w; lowestName = e.getName(); }
            if (e.getCreatedAt() != null
                    && e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    .getMonthValue() == now.getMonthValue()) {
                createdThisMonth++;
            }
        }

        int archivedThisMonth = 0;
        for (MemoryEntry e : archived) {
            if (e.getCreatedAt() != null
                    && e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    .getMonthValue() == now.getMonthValue()) {
                archivedThisMonth++;
            }
        }

        return new MemoryStats(
                active.size(), archived.size(), byType,
                active.isEmpty() ? 0 : totalWeight / active.size(),
                highestName, lowestName, nearDormant,
                createdThisMonth, archivedThisMonth, totalReinforced, null);
    }

    /**
     * 记忆导览：根据当前查询从索引中提炼 3-5 个可能相关的主题摘要。
     * 轻量 LLM 调用，只返回一句话预览，不展开完整记忆。
     */
    public String summary(String query) throws IOException {
        String indexContent = index.readIndexContent(memoryDir);
        if (indexContent.isBlank()) return "";

        MemoryStats s = stats();
        try {
            String raw = llm.rawCall("""
                    你是记忆导览助手。根据用户查询和记忆索引，输出一行中文摘要。

                    规则：
                    1. 列出 3-5 个可能相关的主题（一句话即可）
                    2. 如果记忆很多，只挑最相关的
                    3. 不要返回 JSON，直接返回自然语言

                    格式示例："你可能想回顾：① 测试规范（不要 mock 数据库）② Java 偏好（WebFlux 响应式）"

                    记忆健康度：%s

                    索引：\n%s
                    """.formatted(s.healthSummary(), indexContent), "你是记忆导览助手。只返回一行中文摘要，列出当前查询可能相关的记忆主题。");
            return raw != null ? raw.trim() : "";
        } catch (Exception e) {
            log.warn("导览生成失败: {}", e.getMessage());
            return "";
        }
    }

    // ── 归档管理 ──────────────────────────────────────────

    /**
     * 列出所有归档记忆。
     */
    public List<MemoryEntry> listArchived() throws IOException {
        Path archiveDir = memoryDir.resolve(".archive");
        if (!Files.exists(archiveDir)) return List.of();

        List<MemoryEntry> entries = new ArrayList<>();
        try (var files = Files.list(archiveDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(Files::isRegularFile).toList()) {
                try {
                    entries.add(storage.readMemoryFile(f));
                } catch (IOException e) {
                    log.warn("读取归档记忆失败: {}", f);
                }
            }
        }
        entries.sort((a, b) -> Double.compare(b.getWeight(), a.getWeight()));
        return entries;
    }

    // ── 变更历史 ──────────────────────────────────────────

    private void snapshotVersion(MemoryEntry entry) throws IOException {
        Path versionsDir = memoryDir.resolve(".archive").resolve("versions");
        Files.createDirectories(versionsDir);
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        Path snapshot = versionsDir.resolve(ts + "_" + entry.getFilePath().getFileName());
        Files.copy(entry.getFilePath(), snapshot);
        log.debug("版本快照: {}", snapshot.getFileName());
    }

    /**
     * 列出某条记忆的所有历史版本。
     */
    public List<Path> listVersions(String fileName) throws IOException {
        Path versionsDir = memoryDir.resolve(".archive").resolve("versions");
        if (!Files.exists(versionsDir)) return List.of();
        String suffix = "_" + fileName;
        try (var files = Files.list(versionsDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted().collect(Collectors.toList());
        }
    }

    /**
     * 按标签筛选记忆。
     */
    public List<MemoryEntry> listByTag(String tag) throws IOException {
        return loadAllEntries().stream()
                .filter(e -> e.getTags() != null && e.getTags().contains(tag))
                .collect(Collectors.toList());
    }

    /**
     * 所有标签及出现次数。
     */
    public Map<String, Integer> tagStats() throws IOException {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MemoryEntry e : loadAllEntries()) {
            if (e.getTags() != null) {
                for (String t : e.getTags()) {
                    counts.merge(t, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    /**
     * 归档目录路径。
     */
    public Path getArchiveDir() {
        return memoryDir.resolve(".archive");
    }

    // ── Prompt building ────────────────────────────────────

    /**
     * 构建完整的记忆 prompt，用于注入到对话上下文中。
     * 使用 recallWithRelated 自动扩展关联记忆。
     */
    public String buildMemoryPrompt(String query) throws IOException {
        List<MemoryEntry> recalled = recallWithRelated(query);
        return injector.buildMemoryPrompt(memoryDir, query, recalled);
    }

    /**
     * 仅用 MEMORY.md 索引构建 prompt（无召回内容）。
     */
    public String buildIndexPrompt(String query) throws IOException {
        return injector.buildIndexOnlyPrompt(memoryDir, query);
    }

    /**
     * 根据最近的用户消息检查是否应抑制记忆注入。
     */
    public boolean shouldSuppress(List<String> recentMessages) {
        return injector.shouldSuppressMemory(recentMessages);
    }

    // ── Index management ───────────────────────────────────

    /**
     * 强制重建索引。
     */
    public void rebuildIndex() throws IOException {
        List<MemoryEntry> all = loadAllEntries();
        index.generate(memoryDir, all);
        log.info("索引已重建: {} 条", all.size());
    }

    /**
     * 列出完整索引内容。
     */
    public String readIndex() throws IOException {
        return index.readIndexContent(memoryDir);
    }

    /**
     * 列出所有记忆清单条目。
     */
    public List<MemoryManifest> listManifest() throws IOException {
        return index.scanManifest(memoryDir);
    }

    // ── CRUD ───────────────────────────────────────────────

    /**
     * 加载所有记忆条目的完整内容。
     */
    public List<MemoryEntry> loadAllEntries() throws IOException {
        List<Path> files = storage.listMemoryFiles(memoryDir);
        List<MemoryEntry> entries = new ArrayList<>();
        for (Path file : files) {
            try {
                entries.add(storage.readMemoryFile(file));
            } catch (IOException e) {
                log.warn("读取记忆文件失败: {}", file, e);
            }
        }
        return entries;
    }

    /**
     * 按名称删除一条记忆。
     */
    public boolean delete(String name) throws IOException {
        Path filePath = memoryDir.resolve(sanitizeFileName(name) + ".md");
        boolean deleted = storage.deleteMemoryFile(filePath);
        if (deleted) {
            cleanupDeadReferences(sanitizeFileName(name) + ".md");
            index.invalidateCache();
            rebuildIndex();
            log.info("已删除记忆: {}", name);
        }
        return deleted;
    }

    // ── Helpers ────────────────────────────────────────────

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^\\p{L}\\p{N}_\\-]", "_")
                .replaceAll("_+", "_")
                .toLowerCase();
    }

    private String mergeDescription(String existing, String newDesc) {
        if (existing == null || existing.isBlank()) return newDesc;
        if (newDesc == null || newDesc.isBlank()) return existing;
        return existing.length() > newDesc.length() ? existing : newDesc;
    }
}
