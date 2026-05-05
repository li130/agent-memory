package com.wuuees.li.memory.kairos;

import com.wuuees.li.memory.model.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * KAIROS 日志追加器。
 *
 * 白天 AI 对话不直接写入记忆文件，而是追加到当日日志。
 * 格式：logs/YYYY/MM/YYYY-MM-DD.md
 *
 * 夜间由 {@link NightlyDistiller} 蒸馏成结构化记忆。
 */
public class KairosLogger {

    private static final Logger log = LoggerFactory.getLogger(KairosLogger.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path logsDir;
    private final boolean enabled;

    public KairosLogger(Path memoryDir, boolean enabled) {
        this.logsDir = memoryDir.resolve("logs");
        this.enabled = enabled;
        if (enabled) {
            log.info("KAIROS 日志模式已启用: {}", logsDir);
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 追加一条对话记录到当日日志文件。
     *
     * @param speaker 发言者（"user" / "assistant" / "system"）
     * @param content 对话内容
     * @param type    自动分类提示（可为 null）
     */
    public void append(String speaker, String content, MemoryType type) {
        if (!enabled) return;
        try {
            Path logFile = todayLogFile();
            Files.createDirectories(logFile.getParent());

            StringBuilder entry = new StringBuilder();
            entry.append("## ").append(LocalTime.now().format(TIME_FMT))
                    .append(" ").append(speaker.toUpperCase());
            if (type != null) {
                entry.append(" [").append(type.name()).append("]");
            }
            entry.append("\n\n").append(content).append("\n\n");

            Files.writeString(logFile, entry.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("追加 KAIROS 日志失败: {}", e.getMessage());
        }
    }

    /**
     * 返回当日的日志文件路径。
     */
    public Path todayLogFile() {
        LocalDate today = LocalDate.now();
        return logsDir
                .resolve(String.valueOf(today.getYear()))
                .resolve(String.format("%02d", today.getMonthValue()))
                .resolve(today.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md");
    }

    /**
     * 读取指定日期的日志内容。
     */
    public String readLog(LocalDate date) throws IOException {
        Path logFile = logsDir
                .resolve(String.valueOf(date.getYear()))
                .resolve(String.format("%02d", date.getMonthValue()))
                .resolve(date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md");
        if (!Files.exists(logFile)) return "";
        return Files.readString(logFile, StandardCharsets.UTF_8);
    }

    /**
     * 列出所有有日志的日期。
     */
    public java.util.List<LocalDate> listLoggedDates() throws IOException {
        if (!Files.exists(logsDir)) return java.util.List.of();

        java.util.List<LocalDate> dates = new java.util.ArrayList<>();
        try (var years = Files.list(logsDir)) {
            years.filter(Files::isDirectory).forEach(yearDir -> {
                try (var months = Files.list(yearDir)) {
                    months.filter(Files::isDirectory).forEach(monthDir -> {
                        try (var files = Files.list(monthDir)) {
                            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                                    .forEach(f -> {
                                        String name = f.getFileName().toString().replace(".md", "");
                                        try {
                                            dates.add(LocalDate.parse(name));
                                        } catch (Exception ignored) {}
                                    });
                        } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            });
        }
        dates.sort(java.util.Comparator.naturalOrder());
        return dates;
    }
}
