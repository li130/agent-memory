package com.wuuees.li.memory.recall;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.wuuees.li.memory.model.MemoryManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆召回器，通过 LLM 侧查询从候选清单中选出最相关的 Top-N 记忆。
 * 网关层保证 LLM 可用，服务内部不做降级。
 */
public class MemoryRecall {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecall.class);

    static final int DEFAULT_MAX_RESULTS = 5;

    private final SideQueryClient llm;

    public MemoryRecall(SideQueryClient llm) {
        this.llm = llm;
    }

    /**
     * 召回相关记忆，LLM 侧查询直接从候选清单中选取。
     */
    public List<MemoryManifest> recall(String query, List<MemoryManifest> candidates,
                                       RecallOptions options) {
        if (candidates.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> recentTools = options != null ? options.recentTools : Set.of();
        Set<String> alreadySurfaced = options != null ? options.alreadySurfaced : Set.of();

        List<String> recentList = recentTools != null ? List.copyOf(recentTools) : List.of();
        List<String> surfacedList = alreadySurfaced != null ? List.copyOf(alreadySurfaced) : List.of();
        int maxResults = options != null ? options.maxResults : DEFAULT_MAX_RESULTS;
        List<String> paths = llm.recall(query, candidates, recentList, surfacedList, maxResults);

        List<MemoryManifest> results = new ArrayList<>();
        for (String path : paths) {
            for (MemoryManifest m : candidates) {
                if (m.getPath().toString().endsWith(path)
                        || m.getPath().getFileName().toString().equals(path)) {
                    results.add(m);
                    break;
                }
            }
        }

        log.debug("LLM 召回: {} 条结果", results.size());
        return results;
    }

    public static class RecallOptions {
        int maxResults = DEFAULT_MAX_RESULTS;
        Set<String> recentTools = Set.of();
        Set<String> alreadySurfaced = Set.of();

        public RecallOptions maxResults(int v) { this.maxResults = v; return this; }
        public RecallOptions recentTools(Set<String> v) { this.recentTools = v; return this; }
        public RecallOptions alreadySurfaced(Set<String> v) { this.alreadySurfaced = v; return this; }
    }
}
