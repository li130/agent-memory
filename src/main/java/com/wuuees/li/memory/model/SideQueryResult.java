package com.wuuees.li.memory.model;

import java.util.Collections;
import java.util.List;

/**
 * LLM 侧查询的返回结果。
 * 一次 sideQuery 同时完成：动作判断 + 类型分类 + 去重检查 + 召回路径。
 */
public class SideQueryResult {

    public enum Action { SAVE, RECALL, IGNORE }

    private Action action = Action.RECALL;
    private MemoryType memoryType;
    private String name;
    private String description;
    private String similarTo;
    private List<String> relevantPaths = Collections.emptyList();
    private String reason;

    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }

    public MemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(MemoryType memoryType) { this.memoryType = memoryType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSimilarTo() { return similarTo; }
    public void setSimilarTo(String similarTo) { this.similarTo = similarTo; }

    public List<String> getRelevantPaths() { return relevantPaths; }
    public void setRelevantPaths(List<String> relevantPaths) { this.relevantPaths = relevantPaths; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @Override
    public String toString() {
        return "SideQueryResult{action=" + action + ", type=" + memoryType + ", name=" + name + '}';
    }
}
