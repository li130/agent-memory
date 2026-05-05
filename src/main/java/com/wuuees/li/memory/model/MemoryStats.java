package com.wuuees.li.memory.model;

import java.time.LocalDate;
import java.util.Map;

/**
 * 记忆系统统计快照。
 */
public record MemoryStats(
        int totalActive,
        int totalArchived,
        Map<MemoryType, Integer> byType,
        double avgWeight,
        String highestWeight,
        String lowestWeight,
        int nearDormant,
        int createdThisMonth,
        int archivedThisMonth,
        int totalReinforced,
        LocalDate lastDistill
) {
    public double activeRate() {
        return totalActive + totalArchived > 0
                ? (double) totalActive / (totalActive + totalArchived)
                : 1.0;
    }

    public double dormancyRate() {
        return totalActive > 0 ? (double) nearDormant / totalActive : 0.0;
    }

    public String healthSummary() {
        return String.format(
                "记忆健康度: 活跃%d条 归档%d条 | 平均权重%.2f | 濒危%d条(%.0f%%) | 本月新建%d 归档%d | 累计强化%d次",
                totalActive, totalArchived, avgWeight,
                nearDormant, dormancyRate() * 100,
                createdThisMonth, archivedThisMonth, totalReinforced);
    }
}
