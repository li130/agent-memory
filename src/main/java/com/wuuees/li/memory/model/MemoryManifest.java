package com.wuuees.li.memory.model;

import java.nio.file.Path;

public class MemoryManifest {

    private String name;
    private String description;
    private MemoryType type;
    private Path path;
    private long mtimeMs;

    public MemoryManifest() {
    }

    public MemoryManifest(String name, String description, MemoryType type,
                          Path path, long mtimeMs) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.path = path;
        this.mtimeMs = mtimeMs;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public MemoryType getType() { return type; }
    public void setType(MemoryType type) { this.type = type; }

    public Path getPath() { return path; }
    public void setPath(Path path) { this.path = path; }

    public long getMtimeMs() { return mtimeMs; }
    public void setMtimeMs(long mtimeMs) { this.mtimeMs = mtimeMs; }

    /**
     * 计算时间衰减分数：0.9^(days/7)，越旧的记忆权重越低。
     */
    public double decayScore(int daysSinceCreated) {
        return Math.pow(0.9, daysSinceCreated / 7.0);
    }

    @Override
    public String toString() {
        return "MemoryManifest{name='" + name + "', type=" + type + "}";
    }
}
