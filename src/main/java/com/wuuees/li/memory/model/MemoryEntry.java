package com.wuuees.li.memory.model;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MemoryEntry {

    private String name;
    private String description;
    private MemoryType type;
    private Path filePath;
    private String content;
    private Instant createdAt;
    private Instant updatedAt;
    private List<String> related = new ArrayList<>();

    // ── 生命周期字段 ──
    /** 权重 0.0~1.0，新建记忆默认 0.7，核心记忆 > 0.9，沉睡记忆 < 0.2 */
    private double weight = 0.7;
    /** 被强化次数（每次被召回确认 +1） */
    private int reinforced;
    /** 最后一次被召回的时间 */
    private Instant lastRecalled;
    /** 每次衰减的比例，默认 0.05（每周期减 5%） */
    private double decayRate = 0.05;
    /** 记忆重要性，影响初始权重和衰减速度 */
    private Importance importance = Importance.NORMAL;
    /** 标签列表（逻辑分组，不改变物理存储） */
    private List<String> tags = new ArrayList<>();
    /** 这条记忆从哪条进化而来（文件名），没有则为 null */
    private String evolvedFrom;

    public enum Importance {
        /** 核心记忆：初始 weight=0.95，衰减极慢 */
        CRITICAL,
        /** 重要记忆：初始 weight=0.80，衰减较慢 */
        HIGH,
        /** 普通记忆：初始 weight=0.70，正常衰减 */
        NORMAL,
        /** 临时记忆：初始 weight=0.50，衰减较快 */
        TEMPORAL;

        public double initialWeight() {
            return switch (this) {
                case CRITICAL -> 0.95;
                case HIGH -> 0.80;
                case NORMAL -> 0.70;
                case TEMPORAL -> 0.50;
            };
        }

        public double defaultDecayRate() {
            return switch (this) {
                case CRITICAL -> 0.01;
                case HIGH -> 0.03;
                case NORMAL -> 0.05;
                case TEMPORAL -> 0.10;
            };
        }
    }

    public MemoryEntry() {
    }

    public MemoryEntry(String name, String description, MemoryType type,
                       Path filePath, String content,
                       Instant createdAt, Instant updatedAt) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.filePath = filePath;
        this.content = content;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public MemoryType getType() { return type; }
    public void setType(MemoryType type) { this.type = type; }

    public Path getFilePath() { return filePath; }
    public void setFilePath(Path filePath) { this.filePath = filePath; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<String> getRelated() { return related; }
    public void setRelated(List<String> related) { this.related = related; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = clamp(weight); }

    public int getReinforced() { return reinforced; }
    public void setReinforced(int reinforced) { this.reinforced = reinforced; }

    public Instant getLastRecalled() { return lastRecalled; }
    public void setLastRecalled(Instant lastRecalled) { this.lastRecalled = lastRecalled; }

    public double getDecayRate() { return decayRate; }
    public void setDecayRate(double decayRate) { this.decayRate = clampRate(decayRate); }

    public Importance getImportance() { return importance; }
    public void setImportance(Importance importance) {
        this.importance = importance;
        if (importance != null) {
            this.weight = importance.initialWeight();
            this.decayRate = importance.defaultDecayRate();
        }
    }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getEvolvedFrom() { return evolvedFrom; }
    public void setEvolvedFrom(String evolvedFrom) { this.evolvedFrom = evolvedFrom; }

    // ── 生命周期方法 ──

    /** 年龄（天） */
    public long ageDays() {
        if (createdAt == null) return 0;
        return ChronoUnit.DAYS.between(createdAt, Instant.now());
    }

    /** 距上次召回已过天数 */
    public long daysSinceRecall() {
        if (lastRecalled == null) return ageDays();
        return ChronoUnit.DAYS.between(lastRecalled, Instant.now());
    }

    /** 综合评分：weight × 时效衰减 × 强化加成 */
    public double relevanceScore() {
        double recency = Math.max(0.3, 1.0 - daysSinceRecall() * 0.02);
        double reinforceBonus = 1.0 + reinforced * 0.05;
        return weight * recency * reinforceBonus;
    }

    /** 是否活跃（可以被正常召回） */
    public boolean isActive() { return weight >= 0.3; }

    /** 是否沉睡（应归档） */
    public boolean isDormant() { return weight < 0.2; }

    /** 强化：每次被正确召回时 +0.02，上限 1.0 */
    public void reinforce() {
        this.weight = clamp(this.weight + 0.02);
        this.reinforced++;
        this.lastRecalled = Instant.now();
    }

    /** 衰减一次 */
    public void decay() {
        this.weight = clamp(this.weight * (1.0 - decayRate));
    }

    /** 刷新时间戳 */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double clampRate(double v) { return Math.max(0.0, Math.min(0.5, v)); }

    // ── 序列化 ──

    /**
     * 将 MemoryEntry 序列化为带 YAML frontmatter 的文件内容。
     */
    public String toFileContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escapeYaml(name)).append("\n");
        sb.append("description: ").append(escapeYaml(description != null ? description : "")).append("\n");
        sb.append("type: ").append(type.name().toLowerCase()).append("\n");
        if (createdAt != null) {
            sb.append("createdAt: ").append(createdAt.toString()).append("\n");
        }
        if (updatedAt != null) {
            sb.append("updatedAt: ").append(updatedAt.toString()).append("\n");
        }
        sb.append("importance: ").append(importance.name()).append("\n");
        sb.append("weight: ").append(String.format("%.2f", weight)).append("\n");
        if (reinforced > 0) sb.append("reinforced: ").append(reinforced).append("\n");
        if (lastRecalled != null) sb.append("lastRecalled: ").append(lastRecalled.toString()).append("\n");
        sb.append("decayRate: ").append(String.format("%.2f", decayRate)).append("\n");
        if (evolvedFrom != null) sb.append("evolvedFrom: ").append(evolvedFrom).append("\n");
        if (tags != null && !tags.isEmpty()) {
            sb.append("tags:");
            for (String t : tags) sb.append(" ").append(t);
            sb.append("\n");
        }
        if (related != null && !related.isEmpty()) {
            sb.append("related:");
            for (String r : related) sb.append(" ").append(r);
            sb.append("\n");
        }
        sb.append("---\n\n");
        if (content != null) {
            sb.append(content);
            if (!content.endsWith("\n")) sb.append("\n");
        }
        return sb.toString();
    }

    private static String escapeYaml(String value) {
        if (value == null) return "";
        if (value.contains(":") || value.contains("#") || value.contains("\"")
                || value.contains("'") || value.startsWith(" ") || value.endsWith(" ")
                || value.contains("{") || value.contains("}") || value.contains("[")
                || value.contains("]") || value.contains(",") || value.contains("&")
                || value.contains("*") || value.contains("?") || value.contains("|")
                || value.contains("-") || value.contains("<") || value.contains(">")
                || value.contains("=") || value.contains("!") || value.contains("%")
                || value.contains("@") || value.contains("`")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryEntry that)) return false;
        return Objects.equals(name, that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return "MemoryEntry{name='" + name + "', type=" + type
                + ", weight=" + String.format("%.2f", weight)
                + ", reinforced=" + reinforced + "}";
    }
}
